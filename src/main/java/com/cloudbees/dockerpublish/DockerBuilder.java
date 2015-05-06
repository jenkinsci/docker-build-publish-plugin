package com.cloudbees.dockerpublish;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectStreamException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.CheckForNull;
import javax.servlet.ServletException;

import org.apache.commons.io.output.TeeOutputStream;
import org.jenkinsci.plugins.docker.commons.KeyMaterial;
import org.jenkinsci.plugins.docker.commons.DockerRegistryEndpoint;
import org.jenkinsci.plugins.docker.commons.DockerServerEndpoint;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
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

    private static final Pattern IMAGE_BUILT_PATTERN = Pattern.compile("Successfully built ([0-9a-f]+)");

    @CheckForNull
    private final DockerServerEndpoint server;
    @CheckForNull
    private DockerRegistryEndpoint registry;
    private String repoName;
    private boolean noCache;
    private boolean forcePull;
    @CheckForNull
    private String dockerfilePath;
    private boolean skipBuild;
    private boolean skipDecorate;
    @CheckForNull
    private String repoTag;
    private boolean skipPush = true;
    private boolean skipTagLatest;

    @Deprecated
    public DockerBuilder(String repoName, String repoTag, boolean skipPush, boolean noCache, boolean forcePull, boolean skipBuild, boolean skipDecorate, boolean skipTagLatest, String dockerfilePath) {
        this(null, null, repoName);
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
    public DockerBuilder(DockerServerEndpoint server, DockerRegistryEndpoint registry, String repoName) {
        this.server = server;
        this.registry = registry;
        this.repoName = repoName;
    }

    public DockerServerEndpoint getServer() {
        return server;
    }

    public DockerRegistryEndpoint getRegistry() {
        return registry;
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

    public String getDockerfilePath() {
        return dockerfilePath;
    }

    @DataBoundSetter
    public void setDockerfilePath(String dockerfilePath) {
        this.dockerfilePath = dockerfilePath;
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
    public void setSkipTagLatest(boolean skipTagLatest) {
        this.skipTagLatest = skipTagLatest;
    }

    /**
     * Fully qualified repository/image name with the registry url in front
     * @return ie. docker.acme.com/jdoe/busybox
     * @throws IOException
     */
    public String getRepo() throws IOException {
        if (registry == null) {
            // after upgrading it can be null
            return repoName;
        }
        return registry.imageName(repoName);
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
    	final String stdout;
    	
    	private Result() {
    		this(true, "");
    	}
    	
    	private Result(boolean result, String stdout) {
    		this.result = result;
    		this.stdout = stdout;
    	}
    }

    @CheckForNull
    static String getImageBuiltFromStdout(CharSequence stdout) {
        Matcher m = IMAGE_BUILT_PATTERN.matcher(stdout);
        return m.find() ? m.group(1) : null;
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
                	for (String tag: getNameAndTag()) {
                		build.setDisplayName(build.getDisplayName() + " " + tag);
                	}
                }

                return
                    (isSkipBuild() ? maybeTagOnly() : buildAndTag()) &&
                    (isSkipPush() ? true : dockerPushCommand());

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
        private List<String> getNameAndTag() throws MacroEvaluationException, IOException, InterruptedException {
        	List<String> tags = new ArrayList<String>();
            if (!defined(getRepoTag())) {
                tags.add(expandAll(getRepo()));
            } else {
            	for (String rt: expandAll(getRepoTag()).trim().split(",")) {
                    tags.add(expandAll(getRepo() + ":" + rt));
            	}
            	if (!isSkipTagLatest()) {
                    tags.add(expandAll(getRepo() + ":latest"));
            	}
            }
        	return tags;
        }
        
        private boolean maybeTagOnly() throws MacroEvaluationException, IOException, InterruptedException {
        	List<String> result = new ArrayList<String>();
            if (!defined(getRepoTag())) {
                result.add("echo 'Nothing to build or tag'");
            } else {
            	for (String tag : getNameAndTag()) {
                    result.add("docker tag " + getRepo() + " " + tag);
            	}
            }
            return executeCmd(result);
        }
        
		private boolean buildAndTag() throws MacroEvaluationException, IOException, InterruptedException {
			String context = defined(getDockerfilePath()) ?
					getDockerfilePath() : ".";
			Iterator<String> i = getNameAndTag().iterator();
			Result lastResult = new Result();
			if (i.hasNext()) {
				lastResult = executeCmd("docker build -t " + i.next()
						+ ((isNoCache()) ? " --no-cache=true " : "") + " "
						+ ((isForcePull()) ? " --pull=true " : "") + " "
						+ context);
			}
			// get the image to save rebuilding it to apply the other tags
			String image = getImageBuiltFromStdout(lastResult.stdout);
			if (image != null) {
				// we know the image name so apply the tags directly
				while (lastResult.result && i.hasNext()) {
					lastResult = executeCmd("docker tag --force=true " + image + " " + i.next());
				}
			} else {
				// we don't know the image name so rebuild the image for each tag
				while (lastResult.result && i.hasNext()) {
					lastResult = executeCmd("docker build -t " + i.next()
							+ ((isNoCache()) ? " --no-cache=true " : "") + " "
							+ ((isForcePull()) ? " --pull=true " : "") + " "
							+ context);
				}
			}
			return lastResult.result;
		}

        private boolean dockerPushCommand() throws InterruptedException, MacroEvaluationException, IOException {
        	List<String> result = new ArrayList<String>();
        	for (String tag: getNameAndTag()) {
        		result.add("docker push " + tag);
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

        private Result executeCmd(String cmd) throws IOException, InterruptedException {
            if (registry == null) {
                // right after an upgrade
                throw new IllegalStateException("Docker registry is not configured");
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            TeeOutputStream stdout = new TeeOutputStream(listener.getLogger(), baos);
            PrintStream stderr = listener.getLogger();

            // get Docker registry credentials
            KeyMaterial registryKey = registry.newKeyMaterialFactory(build).materialize();
            // Docker server credentials. If server is null (right after upgrading) do not use credentials
            KeyMaterial serverKey = server == null ? null : server.newKeyMaterialFactory(build).materialize();

            logger.log(Level.FINER, "Executing: {0}", cmd);

            try {
                EnvVars env = new EnvVars();
                env.putAll(build.getEnvironment(listener));
                env.putAll(registryKey.env());
                if (serverKey != null) {
                    env.putAll(serverKey.env());
                }
    
                boolean result = launcher.launch()
                        .envs(env)
                        .pwd(build.getWorkspace())
                        .stdout(stdout)
                        .stderr(stderr)
                        .cmdAsSingleString(cmd)
                        .start().join() == 0;

                // capture the stdout so it can be parsed later on
                String stdoutStr = null;
                try {
                    Computer computer = Computer.currentComputer();
                    if (computer != null) {
                        Charset charset = computer.getDefaultCharset();
                        if (charset != null) {
                            baos.flush();
                            stdoutStr = baos.toString(charset.name());
                        }
                    }
                } catch (UnsupportedEncodingException e) {
                    // we couldn't parse, ignore
                    logger.log(Level.FINE, "Unable to get stdout from launched command: {}", e);
                }

                return new Result(result, stdoutStr);

            } finally {
                registryKey.close();
                if (serverKey != null) {
                    serverKey.close();
                }
            }
        }

        private boolean recordException(Exception e) {
            listener.error(e.getMessage());
            e.printStackTrace(listener.getLogger());
            return false;
        }
    	
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

        // Using docker-commons now, methods left for backwards compatibility

        public String getUserName() {
            return userName;
        }
        public String getPassword() {
            return password;
        }
        public String getEmail() { return email; }
        public String getRegistryUrl() { return registryUrl; }

        private transient String userName;
        private transient String password;
        private transient String email;
        private transient String registryUrl;

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
