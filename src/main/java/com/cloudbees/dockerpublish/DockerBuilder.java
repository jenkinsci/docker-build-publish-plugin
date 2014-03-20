package com.cloudbees.dockerpublish;
import hudson.Launcher;
import hudson.Extension;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.AbstractProject;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;

/**
 * Plugin to build and publish docker projects to the docker registry/index.
 * This can optionally push, and bust cache.
 *
 * @author Michael Neale
 */
public class DockerBuilder extends Builder {
    private final String repoName;
    private final boolean noCache;
    private String repoTag;
    private boolean skipPush = true;

    /**
    *
    * See <tt>src/main/resources/hudson/plugins/hello_world/DockerBuilder/config.jelly</tt>
    * for the actual HTML fragment for the configuration screen.
    */
    @DataBoundConstructor
    public DockerBuilder(String repoName, String repoTag, boolean skipPush, boolean noCache) {
        this.repoName = repoName;
        this.repoTag = repoTag;
        this.skipPush = skipPush;
        this.noCache = noCache;
    }

    public String getRepoName() {return repoName; }
    public String getRepoTag() {  return repoTag; }
    public boolean isSkipPush() { return skipPush;}
    public boolean isNoCache() { return noCache;}




    /**
     * This tag is what is used to build (and tag into the local clone of the repo)
     *   but not to push to the registry.
     * In docker - you push the whole repo to trigger the sync.
     */
    private String getNameAndTag() {
        if (getRepoTag() == null || repoTag.trim().isEmpty()) {
            return repoName;
        } else {
            return repoName + ":" + repoTag;
        }
    }


    /** Mask the password. Future: use oauth token instead with Oauth sign in */
    private ArgumentListBuilder dockerLoginCommand() {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("docker").add("login").add("-u").add(getDescriptor().getUserName()).add("-e").add(getDescriptor().getEmail()).add("-p").addMasked(getDescriptor().getPassword());
        return args;
    }

    private String dockerBuildCommand(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException, MacroEvaluationException {
        String buildTag = TokenMacro.expandAll(build, listener, getNameAndTag());
        return "docker build -q -t " + buildTag + ((isNoCache()) ? " --no-cache=true " : "")  + " .";
    }

    private String dockerPushCommand(AbstractBuild build, BuildListener listener) throws InterruptedException, MacroEvaluationException, IOException {
        return "docker push " + TokenMacro.expandAll(build, listener, getRepoName());
    }


    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener)  {
        try {
            build.setDisplayName(build.getDisplayName() + " " + TokenMacro.expandAll(build, listener, getNameAndTag()));

            return
                maybeLogin(build, launcher, listener) &&
                executeCmd(build, launcher, listener, dockerBuildCommand(build, listener)) &&
                maybePush(build, launcher, listener);

        } catch (IOException e) {
            return recordException(listener, e);
        } catch (InterruptedException e) {
            return recordException(listener, e);
        } catch (MacroEvaluationException e) {
            return recordException(listener, e);
        }

    }

    private boolean maybeLogin(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
        if (getDescriptor().getPassword() == null || getDescriptor().getPassword().isEmpty()) {
            listener.getLogger().println("No credentials provided, so not logging in to the registry.");
            return true;
        } else {
            return executeCmd(build, launcher, listener, dockerLoginCommand());
        }
    }

    private boolean maybePush(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException, MacroEvaluationException {
        if (!isSkipPush()) {
            return executeCmd(build, launcher, listener, dockerPushCommand(build, listener));
        } else {
            return true;
        }
    }

    private boolean executeCmd(AbstractBuild build, Launcher launcher, BuildListener listener, ArgumentListBuilder args) throws IOException, InterruptedException {
        return launcher.launch()
            .envs(build.getEnvironment(listener))
            .pwd(build.getWorkspace())
            .stdout(listener.getLogger())
            .stderr(listener.getLogger())
            .cmds(args)
            .start().join() == 0;
    }


    private boolean executeCmd(AbstractBuild build, Launcher launcher, BuildListener listener, String cmd) throws IOException, InterruptedException {
        return launcher.launch()
                .envs(build.getEnvironment(listener))
                .pwd(build.getWorkspace())
                .stdout(listener.getLogger())
                .stderr(listener.getLogger())
                .cmdAsSingleString(cmd)
                .start().join() == 0;
    }


    private boolean recordException(BuildListener listener, Exception e) {
        listener.error(e.getMessage());
        e.printStackTrace(listener.getLogger());
        return false;
    }


    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link DockerBuilder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     * <p>
     * See <tt>src/main/resources/hudson/plugins/hello_world/DockerBuilder/global.jelly</tt>
     * for the actual HTML fragment for the plugin global config screen.
     */
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public String getUserName() {
            return userName;
        }
        public String getPassword() {
            return password;
        }
        public String getEmail() {
            return email;
        }

        private String userName;
        private String password;
        private String email;

        /**
         * In order to load the persisted global configuration, you have to 
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        /**
         * Performs on-the-fly validation of the form field 'repoName'.
         *
         * @param value
         *      Name of the docker repo (eg michaelneale/foo-bar).
         */
        public FormValidation doCheckRepoName(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set a name");
            if (value.length() < 4)
                return FormValidation.warning("Isn't the name too short?");
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
            return "Docker build and publish";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            // To persist global configuration information,
            // set that to properties and call save().
            userName = formData.getString("userName");
            password = formData.getString("password");
            email = formData.getString("email");
            save();
            return super.configure(req,formData);
        }


    }
}

