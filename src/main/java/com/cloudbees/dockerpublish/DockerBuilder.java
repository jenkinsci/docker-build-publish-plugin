package com.cloudbees.dockerpublish;

import com.cloudbees.dockerpublish.DockerCLIHelper.InspectImageResponse;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.DescriptorExtensionList;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.BuildListener;
import hudson.model.Node;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.util.FormValidation;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectStreamException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.ServletException;

import org.apache.commons.io.output.TeeOutputStream;
import org.jenkinsci.plugins.docker.commons.credentials.KeyMaterial;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;
import org.jenkinsci.plugins.docker.commons.credentials.DockerServerEndpoint;
import org.jenkinsci.plugins.docker.commons.fingerprint.DockerFingerprints;
import org.jenkinsci.plugins.docker.commons.tools.DockerTool;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;


/**
 * Plugin to build and publish docker projects to the docker registry/index.
 * This can optionally push, and bust cache.
 *
 * @author Michael Neale
 */
public class DockerBuilder extends Builder {

    private static final Logger logger = Logger.getLogger(DockerBuilder.class.getName());

    private static final Pattern IMAGE_BUILT_PATTERN = Pattern.compile("Successfully built ([0-9a-f]{12,})");

    private DockerServerEndpoint server;
    private DockerRegistryEndpoint registry;
    private String repoName;
    private boolean noCache;
    private boolean forcePull;
    @CheckForNull
    private String buildContext;
    @CheckForNull
    private String dockerfilePath;
    private boolean skipBuild;
    private boolean skipDecorate;
    @CheckForNull
    private String repoTag;
    private boolean skipPush = true;
    private boolean createFingerprint = true;
    private boolean skipTagLatest;
    private String buildAdditionalArgs = "";
    private boolean forceTag = false;
    
    @CheckForNull
    private String dockerToolName;

    @Deprecated
    public DockerBuilder(String repoName, String repoTag, boolean skipPush, boolean noCache, boolean forcePull, boolean skipBuild, boolean skipDecorate, boolean skipTagLatest, String dockerfilePath) {
        this(repoName);
        this.repoTag = repoTag;
        this.skipPush = skipPush;
        this.noCache = noCache;
        this.forcePull = forcePull;
        this.dockerfilePath = dockerfilePath;
        this.skipBuild = skipBuild;
        this.skipDecorate = skipDecorate;
        this.skipTagLatest = skipTagLatest;
    }

    @DataBoundConstructor
    public DockerBuilder(String repoName) {
        this.server = new DockerServerEndpoint(null, null);
        this.registry = new DockerRegistryEndpoint(null, null);
        this.repoName = repoName;
    }

    public DockerServerEndpoint getServer() {
        return server;
    }

    @DataBoundSetter
    public void setServer(DockerServerEndpoint server) {
        this.server = server;
    }

    public DockerRegistryEndpoint getRegistry() {
        return registry;
    }

    @DataBoundSetter
    public void setRegistry(DockerRegistryEndpoint registry) {
        this.registry = registry;
    }

    public String getRepoName() {
        return repoName;
    }

    public boolean isNoCache() {
        return noCache;
    }

    @DataBoundSetter
    public void setNoCache(boolean noCache) {
        this.noCache = noCache;
    }

    public boolean isForcePull() {
        return forcePull;
    }

    @DataBoundSetter
    public void setForcePull(boolean forcePull) {
        this.forcePull = forcePull;
    }

    public String getBuildContext() {
        return buildContext;
    }

    @DataBoundSetter
    public void setBuildContext(String buildContext) {
        this.buildContext = Util.fixEmptyAndTrim(buildContext);
    }

    public String getDockerfilePath() {
        return dockerfilePath;
    }

    @DataBoundSetter
    public void setDockerfilePath(String dockerfilePath) {
        this.dockerfilePath = Util.fixEmptyAndTrim(dockerfilePath);
    }
    
    public String getBuildAdditionalArgs() {
        return buildAdditionalArgs == null ? "" : buildAdditionalArgs;
    }

    @DataBoundSetter
    public void setBuildAdditionalArgs(String buildAdditionalArgs) {
        this.buildAdditionalArgs = buildAdditionalArgs;
    }

    public boolean isSkipBuild() {
        return skipBuild;
    }

    @DataBoundSetter
    public void setSkipBuild(boolean skipBuild) {
        this.skipBuild = skipBuild;
    }

    public boolean isSkipDecorate() {
        return skipDecorate;
    }

    @DataBoundSetter
    public void setSkipDecorate(boolean skipDecorate) {
        this.skipDecorate = skipDecorate;
    }

    public String getRepoTag() {
        return repoTag;
    }

    @DataBoundSetter
    public void setRepoTag(String repoTag) {
        this.repoTag = repoTag;
    }

    public boolean isSkipPush() {
        return skipPush;
    }

    @DataBoundSetter
    public void setSkipPush(boolean skipPush) {
        this.skipPush = skipPush;
    }

    public boolean isSkipTagLatest() {
        return skipTagLatest;
    }

    @DataBoundSetter
    public void setCreateFingerprint(boolean createFingerprint) {
        this.createFingerprint = createFingerprint;
    }

    public boolean isCreateFingerprint() {
        return createFingerprint;
    }
   
    @DataBoundSetter
    public void setSkipTagLatest(boolean skipTagLatest) {
        this.skipTagLatest = skipTagLatest;
    }

    public boolean isForceTag() {
        return forceTag;
    }

    @DataBoundSetter
    public void setForceTag(boolean forceTag) {
        this.forceTag = forceTag;
    }
    
    public String getDockerToolName() {
		return dockerToolName;
	}
    
    @DataBoundSetter
    public void setDockerToolName(String dockerToolName) {
		this.dockerToolName = dockerToolName;
	}

    /**
     * Fully qualified repository/image name with the registry url in front
     * @return ie. docker.acme.com/jdoe/busybox
     * @throws IOException
     */
    public String getRepo() throws IOException {
        return getRegistry().imageName(repoName);
    }


    private boolean defined(String s) {
        return s != null && !s.trim().isEmpty();
    }
    
    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener)  {
        return new Perform(build, launcher, listener).exec();
    }
    
    private static class Result {
        final boolean result;
        final @Nonnull String stdout;
        final @Nonnull String stderr;

        private Result() {
            this(true, "", "");
        }

        private Result(boolean result, @CheckForNull String stdout, @CheckForNull String stderr) {
            this.result = result;
            this.stdout = hudson.Util.fixNull(stdout);
                this.stderr = hudson.Util.fixNull(stderr);
        }
    }

    @CheckForNull
    static String getImageBuiltFromStdout(CharSequence stdout) {
        Matcher m = IMAGE_BUILT_PATTERN.matcher(stdout);
        String lastmatch = null;
        while (m.find()) {
            lastmatch = m.group(1);
        }
        return lastmatch;
    }
    
    private class Perform {
        private final AbstractBuild build;
        private final Launcher launcher;
        private final BuildListener listener;

        private Perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
            this.build = build;
            this.launcher = launcher;
            this.listener = listener;
        }

        private boolean exec() {
            try {
                if (!isSkipDecorate()) {
                    for (ImageTag imageTag : getImageTags()) {
                        build.setDisplayName(build.getDisplayName() + " " + imageTag);
                    }
                }

                return
                    (isSkipBuild() ? maybeTagOnly() : buildAndTag()) &&
                    (isSkipPush() || dockerPushCommand());

            } catch (IOException e) {
                return recordException(e);
            } catch (InterruptedException e) {
                return recordException(e);
            } catch (MacroEvaluationException e) {
                return recordException(e);
            }
        }

        private String expandAll(String s) throws MacroEvaluationException, IOException, InterruptedException {
            return TokenMacro.expandAll(build, listener, s);
        }

        /**
         * This tag is what is used to build, tag and push the registry.
         */
        private List<ImageTag> getImageTags() throws MacroEvaluationException, IOException, InterruptedException {
            List<ImageTag> tags = new ArrayList<ImageTag>();
            if (!defined(getRepoTag())) {
                tags.add(new ImageTag(expandAll(getRepo())));
            } else {
                for (String rt : expandAll(getRepoTag()).trim().split(",")) {
                    tags.add(new ImageTag(expandAll(getRepo()), expandAll(rt)));
                }
                if (!isSkipTagLatest()) {
                    tags.add(new ImageTag(expandAll(getRepo()), "latest"));
                }
            }
            return tags;
        }
        
        private boolean maybeTagOnly() throws MacroEvaluationException, IOException, InterruptedException {
            if (!defined(getRepoTag())) {
                listener.getLogger().println("Nothing to build or tag");
                return true;
            }
            List<String> result = new ArrayList<String>();
            for (ImageTag imageTag : getImageTags()) {
                result.add("tag " + (isForceTag() ? "--force=true " : "") + getRepo() + " " + imageTag);
            }
            return executeCmd(result);
        }

        private boolean buildAndTag() throws MacroEvaluationException, IOException, InterruptedException {
            FilePath context;
            if (defined(expandAll(getBuildContext()))) {
            	Node builtOn = build.getBuiltOn();
            	
                if (builtOn != null) {
                    context = builtOn.createPath(expandAll(getBuildContext()));
                } else {
                    context = new FilePath(new File(expandAll(getBuildContext())));
                }
            } else {
                context = build.getWorkspace();
            }
            Iterator<ImageTag> i = getImageTags().iterator();
            Result lastResult = new Result();
            if (i.hasNext()) {
                lastResult = executeCmd("build " + expandAll(getBuildAdditionalArgs()) + " -t " + i.next()
                    + ((isNoCache()) ? " --no-cache=true " : "") + " "
                    + ((isForcePull()) ? " --pull=true " : "") + " "
                    + (defined(getDockerfilePath()) ? " --file=" + expandAll(getDockerfilePath()) : "") + " "
                    + "'" + context + "'");
            }
            // get the image to save rebuilding it to apply the other tags
            String image = getImageBuiltFromStdout(lastResult.stdout);
            if (image != null) {
                // we know the image name so apply the tags directly
                while (lastResult.result && i.hasNext()) {
                    lastResult = executeCmd("tag "
                            + (isForceTag() ? "--force=true " : "")
                            + image + " " + i.next());
                }
                processFingerprints(image);
            } else {
                // we don't know the image name so rebuild the image for each tag
                while (lastResult.result && i.hasNext()) {
                    lastResult = executeCmd("build " + expandAll(getBuildAdditionalArgs()) +" -t " + i.next()
                        + ((isNoCache()) ? " --no-cache=true " : "") + " "
                        + ((isForcePull()) ? " --pull=true " : "") + " "
                        + (defined(getDockerfilePath()) ? " --file=" + getDockerfilePath() : "") + " "
                        + "'" + context + "'");
                    processFingerprintsFromStdout(lastResult.stdout);
                }
            }
            return lastResult.result;
        }

        private boolean dockerPushCommand() throws InterruptedException, MacroEvaluationException, IOException {
            List<String> result = new ArrayList<String>();
            for (ImageTag imageTag : getImageTags()) {
                result.add("push " + imageTag.toString());
            }
            return executeCmd(result);
        }

        private boolean executeCmd(List<String> cmds) throws IOException, InterruptedException {
            Iterator<String> i = cmds.iterator();
            Result lastResult = new Result();
            // if a command fails, do not continue
            while (lastResult.result && i.hasNext()) {
                lastResult = executeCmd(i.next());
            }
            return lastResult.result;
        }

        /**
         * Runs Docker command using Docker CLI.
         * In this default implementation STDOUT and STDERR outputs will be printed to build logs.
         * Use {@link #executeCmd(java.lang.String, boolean, boolean)} to alter the behavior.
         * @param cmd Command to be executed
         * @return Execution result
         * @throws IOException Execution error
         * @throws InterruptedException The build has been interrupted
         */
        private Result executeCmd(String cmd) throws IOException, InterruptedException {
            return executeCmd(cmd, true, true);
        }
        
        /**
         * Runs Docker command using Docker CLI.
         * @param cmd Command to be executed (Docker command will be prefixed)
         * @param logStdOut If true, propagate STDOUT to the build log
         * @param logStdErr If true, propagate STDERR to the build log
         * @return Execution result
         * @throws IOException Execution error
         * @throws InterruptedException The build has been interrupted
         */
        private @Nonnull Result executeCmd( @Nonnull String cmd, 
                boolean logStdOut, boolean logStdErr) throws IOException, InterruptedException {
            ByteArrayOutputStream baosStdOut = new ByteArrayOutputStream();
            ByteArrayOutputStream baosStdErr = new ByteArrayOutputStream();
            OutputStream stdout = logStdOut ? 
                    new TeeOutputStream(listener.getLogger(), baosStdOut) : baosStdOut;
            OutputStream stderr = logStdErr ? 
                    new TeeOutputStream(listener.getLogger(), baosStdErr) : baosStdErr;

            
            KeyMaterial dockerKeys = 
                // Docker registry credentials
                getRegistry().newKeyMaterialFactory(build)
            .plus(
                // Docker server credentials. If server is null (right after upgrading) do not use credentials
                server == null ? null : server.newKeyMaterialFactory(build))
            .materialize();

            EnvVars env = new EnvVars();
            env.putAll(build.getEnvironment(listener));
            env.putAll(dockerKeys.env());

            String dockerCmd = "docker";
            
            if (getDockerToolName() != null) {
	            try {
	          		dockerCmd = DockerTool.getExecutable(getDockerToolName(), build.getBuiltOn(), listener, env);
	            } catch (Exception e) {
	            	logger.log(Level.WARNING, "Something failed", e);
	            }
            }
            
            cmd = dockerCmd + " " +cmd;
            
            logger.log(Level.FINER, "Executing: {0}", cmd);

            try {
                
                boolean result = launcher.launch()
                        .envs(env)
                        .pwd(build.getWorkspace())
                        .stdout(stdout)
                        .stderr(stderr)
                        .cmdAsSingleString(cmd)
                        .start().join() == 0;

                // capture the stdout so it can be parsed later on
                final String stdOutStr = DockerCLIHelper.getConsoleOutput(baosStdOut, logger);
                final String stdErrStr = DockerCLIHelper.getConsoleOutput(baosStdErr, logger);
                return new Result(result, stdOutStr, stdErrStr);

            } finally {
                dockerKeys.close();
            }
        }
        
        void processFingerprintsFromStdout(@Nonnull String stdout) throws IOException, InterruptedException {
            if (!createFingerprint) {
                return;
            }
            
            final String image = getImageBuiltFromStdout(stdout);
            if (image == null) {
                return;
            }
            processFingerprints(image);
        }
        
        void processFingerprints(@Nonnull String image) throws IOException, InterruptedException {
            if (!createFingerprint) {
                return;
            }
            
            // Retrieve full image ID using another call
            final Result response = executeCmd("inspect " + image, false, true);
            if (!response.result) {
                return; // Bad result, cannot do anything
            }
            final InspectImageResponse rsp = DockerCLIHelper.parseInspectImageResponse(response.stdout);
            logger.log(Level.FINEST, "Inspect image {0}: {1}", new Object[] { image, response.stdout });
            if (rsp == null) {
                return; // Cannot process the data
            }
            
            //  Create or retrieve the fingerprint
            // parent can be ""
            DockerFingerprints.addFromFacet(Util.fixEmpty(rsp.getParent()), rsp.getId(), build);
            
        }

        private boolean recordException(Exception e) {
            listener.error(e.getMessage());
            e.printStackTrace(listener.getLogger());
            return false;
        }

    }

    private Object readResolve() throws ObjectStreamException {
        // coming from an older version <1.0 ? let's try to parse the registry
        if (registry == null) {
            registry = DockerRegistryEndpoint.fromImageName(repoName, null);
            if (registry.getUrl() != null) {
                repoName = repoName.substring(repoName.indexOf('/') + 1); // take out the host:port part
                logger.log(
                        Level.WARNING,
                        "Using Docker registry from old configuration field, you may need to configure credentials in the build step: {0} {1}",
                        new String[] { registry.getUrl(), repoName });
            }
        }
        return this;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link DockerBuilder}. Used as a singleton.
     */
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        @SuppressWarnings("unused")
        private transient String userName;
        @SuppressWarnings("unused")
        private transient String password;
        @SuppressWarnings("unused")
        private transient String email;
        @SuppressWarnings("unused")
        private transient String registryUrl;

        /**
         * In order to load the persisted global configuration, you have to 
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        // Using docker-commons now, methods left for backwards compatibility

        @SuppressFBWarnings(value = "UWF_UNWRITTEN_FIELD", justification = "Methods left for backwards compatibility")
        @SuppressWarnings("unused")
        @Restricted(NoExternalUse.class)
        public String getUserName() {
            return userName;
        }
        @SuppressWarnings("unused")
        @Restricted(NoExternalUse.class)
        public String getPassword() {
            return password;
        }
        @SuppressWarnings("unused")
        @Restricted(NoExternalUse.class)
        public String getEmail() { return email; }
        @SuppressWarnings("unused")
        @Restricted(NoExternalUse.class)
        public String getRegistryUrl() { return registryUrl; }

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
            return "Docker Build and Publish";
        }

       private Object readResolve()
                throws ObjectStreamException {
            // TODO if we want to retain backwards compatibility we need to create the registry credentials
            // here, taking the old global fields and creating the credentials
            // new DockerRegistryEndpoint(getRegistryUrl(), null);
            // new UsernamePasswordCredentials(getUserName(), getPassword());
            return this;
        }

    }
}
