package org.jenkinsci.plugins.bitbucketserver;

import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;

import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.RequestBody;

import jenkins.model.Jenkins;
import hudson.Launcher;
import hudson.Extension;
import hudson.util.FormValidation;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.plugins.git.util.BuildData;
import hudson.security.ACL;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.util.ListBoxModel;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.io.PrintStream;

import com.cloudbees.plugins.credentials.common.StandardUsernameListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.model.Item;
import hudson.model.Job;
import java.util.List;
import org.kohsuke.stapler.AncestorInPath;

public class BitbucketServerNotifier extends Notifier {

    private final OkHttpClient httpClient;

    private String baseUrl;
    private boolean updateSuccess;
    private boolean updateFailure;
    private String credentialsId;
    private String jobDescription;

    public String getBaseUrl() {
        return baseUrl;
    }

    public boolean getUpdateSuccess() {
        return updateSuccess;
    }

    public boolean getUpdateFailure() {
        return updateFailure;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public String getJobDescription() {
        return jobDescription;
    }

    public String getGlobalBaseUrl() {
        return getDescriptor().getGlobalBaseUrl();
    }

    @DataBoundConstructor
    public BitbucketServerNotifier(final String baseUrl, final boolean updateSuccess,
            final boolean updateFailure, final String credentialsId,
            final String jobDescription) {
        String cleanedBaseUrl = baseUrl.trim();
        if (cleanedBaseUrl.endsWith("/")) {
            cleanedBaseUrl = cleanedBaseUrl.substring(0, cleanedBaseUrl.length() - 1);
        }
        this.baseUrl = cleanedBaseUrl;
        this.updateSuccess = updateSuccess;
        this.updateFailure = updateFailure;
        this.credentialsId = credentialsId;
        this.jobDescription = jobDescription;

        httpClient = new OkHttpClient().newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
        boolean success = build.getResult().ordinal == Result.SUCCESS.ordinal;
        boolean failure = build.getResult().ordinal == Result.FAILURE.ordinal;
        BuildData buildData = build.getAction(BuildData.class);

        // data for the payload
        String state = success ? "SUCCESSFUL" : "FAILED";
        String key = build.getProject().getDisplayName();
        String name = "Build #" + build.getId();
        String buildUrl = Jenkins.getInstance().getRootUrl() + build.getUrl();
        String commitHash = buildData.getLastBuiltRevision().getSha1String();

        List<DomainRequirement> uriRequirements = URIRequirementBuilder.fromUri(baseUrl).build();

        UsernamePasswordCredentials creds = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(UsernamePasswordCredentials.class, Jenkins.getInstance(), ACL.SYSTEM, uriRequirements),
                CredentialsMatchers.withId(credentialsId)
        );

        PrintStream logger = listener.getLogger();

        if ((success && updateSuccess) || (failure && updateFailure)) {
            String authHeader = Credentials.basic(creds.getUsername(), creds.getPassword().getPlainText());
            String url = createBuildStatusUrl(baseUrl.length() == 0 ? getGlobalBaseUrl() : baseUrl, commitHash);
            String statusUpdateBody = buildStatusBody(state, key, name, buildUrl, jobDescription);
            logger.println("Bitbucket Server Notifier POSTING to " + url);
            logger.println("BODY " + statusUpdateBody);
            return bitbucketApiCall(logger, url, statusUpdateBody, authHeader);
        }

        return true;
    }

    private String createBuildStatusUrl(String baseUrl, String commitHash) {
        return baseUrl + "/rest/build-status/1.0/commits/" + commitHash;
    }

    private boolean bitbucketApiCall(PrintStream logger, String url, String body, String authHeader) {
        RequestBody jsonBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), body);
        Request request = new Request.Builder()
                .header("Authorization", authHeader)
                .url(url)
                .method("POST", jsonBody)
                .build();
        try {
            Response response = httpClient.newCall(request).execute();
            if (response.isSuccessful()) {
                logger.println("Bitbucket API Success: " + response.code() + " - " + response.message());
                logger.println(response.body().string());
                return true;
            } else {
                logger.println("Bitbucket API Fail: " + response.code() + " - " + response.message());
                logger.println(response.body().string());
                return false;
            }
        } catch (IOException e) {
            e.printStackTrace(logger);
            return false;
        }
    }

    private String buildStatusBody(String state, String key, String name, String url, String desc) {
        return "{"
                + "\"state\": \"" + state + "\","
                + "\"key\": \"" + key + "\","
                + "\"name\": \"" + name + "\","
                + "\"url\": \"" + url + "\","
                + "\"description\": \"" + desc + "\""
                + "}";
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        private String globalBaseUrl = "";

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Job<?, ?> owner, @QueryParameter String baseUrl) {
            if (owner == null || !owner.hasPermission(Item.CONFIGURE)) {
                return new ListBoxModel();
            }
            List<DomainRequirement> uriRequirements = URIRequirementBuilder.fromUri(baseUrl).build();

            return new StandardUsernameListBoxModel()
                    .withEmptySelection()
                    .withAll(CredentialsProvider.lookupCredentials(StandardUsernamePasswordCredentials.class, owner, null, uriRequirements));
        }

        public FormValidation doCheckBaseUrl(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0) {
                if (getGlobalBaseUrl().length() > 0) {
                    return FormValidation.warning("Using Base URL from global settings: " + getGlobalBaseUrl());
                } else {
                    return FormValidation.error("Set the Base URL (or set the global Base URL)");
                }
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckCredentialsId(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0) {
                return FormValidation.error("Please set the bitbucket login credentials");
            }
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Bitbucket Server Notifications";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {

            globalBaseUrl = formData.getString("globalBaseUrl").trim();
            if (globalBaseUrl.endsWith("/")) {
                globalBaseUrl = globalBaseUrl.substring(0, globalBaseUrl.length() - 1);
            }
            save();
            return super.configure(req, formData);
        }

        public String getGlobalBaseUrl() {
            return globalBaseUrl;
        }

    }
}
