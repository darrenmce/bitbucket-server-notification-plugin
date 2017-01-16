package org.jenkinsci.plugins.bitbucketserver;
import hudson.Launcher;
import hudson.Extension;
import hudson.util.FormValidation;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.plugins.git.util.BuildData;
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
import jenkins.model.Jenkins;

public class BitbucketServerNotifier extends Notifier {

    private final String baseUrl;
    private final boolean notifySuccess;
    private final String bitbucketCredentials;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public BitbucketServerNotifier(final String baseUrl, final boolean notifySuccess, final String bitbucketCredentials) {
        this.baseUrl = baseUrl;
        this.notifySuccess = notifySuccess;
        this.bitbucketCredentials = bitbucketCredentials;
    }

    /**
     * We'll use this from the <tt>config.jelly</tt>.
     */
    public String getBaseUrl() {
        return baseUrl;
    }
    
    public boolean getNotifySuccess() {
        return notifySuccess;
    }
    public String getBitbucketCredentials() {
        return bitbucketCredentials;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
        boolean success = build.getResult().ordinal == Result.SUCCESS.ordinal;
        BuildData buildData = build.getAction(BuildData.class);
        String state = success ? "SUCCESSFUL": "FAILED";
        String key = build.getProject().getDisplayName();
        String name = "Build #" + build.getId();
        String buildUrl = Jenkins.getInstance().getRootUrl() + build.getUrl();
        listener.getLogger().println("state" + state);
        listener.getLogger().println(buildData.getLastBuiltRevision().getSha1String());
        listener.getLogger().println(buildStatusBody(state, key, name, buildUrl, "test description"));
        listener.getLogger().println("baseUrl: " + baseUrl);
        listener.getLogger().println("notifySuccess: " + notifySuccess);
        return true;
    }
    
    private String buildStatusBody(String state, String key, String name, String url, String desc) {
        return "{"
                + "\"state\": \""+ state +"\","
                + "\"key\": \""+ key +"\","
                + "\"name\": \""+ name +"\","
                + "\"url\": \""+ url +"\","
                + "\"desc\": \""+ desc +"\""
                + "}";
    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }


    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        
        private boolean useFrench;

        public ListBoxModel doFillTokenbitbucketCredentialsId() {
            
        }
        
        public FormValidation doCheckBaseUrl(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set a base Url");
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
            // To persist global configuration information,
            // set that to properties and call save().
            useFrench = formData.getBoolean("useFrench");
            // ^Can also use req.bindJSON(this, formData);
            //  (easier when there are many fields; need set* methods for this, like setUseFrench)
            save();
            return super.configure(req,formData);
        }

        public boolean getUseFrench() {
            return useFrench;
        }
        
    }
}

