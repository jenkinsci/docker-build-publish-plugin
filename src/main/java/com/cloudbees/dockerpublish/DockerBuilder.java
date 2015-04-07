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

import java.io.PrintStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Plugin to build and publish docker projects to the docker registry/index.
 * This can optionally push, and bust cache.
 *
 * @author Michael Neale
 */
public class DockerBuilder extends Builder {
    private final String repoName;
    private final boolean noCache;
    private final boolean forcePull;
    private final String dockerfilePath;
    private final boolean skipBuild;
    private final boolean skipDecorate;
    private String repoTag;
    private boolean skipPush = true;
    private final boolean skipTagLatest;

    /**
    *
    * See <tt>src/main/resources/hudson/plugins/hello_world/DockerBuilder/config.jelly</tt>
    * for the actual HTML fragment for the configuration screen.
    */
    @DataBoundConstructor
    public DockerBuilder(String repoName, String repoTag, boolean skipPush, boolean noCache, boolean forcePull, boolean skipBuild, boolean skipDecorate, boolean skipTagLatest, String dockerfilePath) {
        this.repoName = repoName;
        this.repoTag = repoTag;
        this.skipPush = skipPush;
        this.noCache = noCache;
        this.forcePull = forcePull;
        this.dockerfilePath = dockerfilePath;
        this.skipBuild = skipBuild;
        this.skipDecorate = skipDecorate;
        this.skipTagLatest = skipTagLatest;
    }

    public String getRepoName() {return repoName; }
    public String getRepoTag() {  return repoTag; }
    public boolean isSkipPush() { return skipPush;}
    public boolean isSkipBuild() { return skipBuild;}
    public boolean isSkipDecorate() { return skipDecorate;}
    public boolean isSkipTagLatest() { return skipTagLatest;}
    public boolean isNoCache() { return noCache;}
    public boolean isForcePull() { return forcePull;}
    public String getDockerfilePath() { return dockerfilePath; }



    private boolean defined(String s) {
    	return s != null && !s.trim().isEmpty();
    }
    
    /** Mask the password. Future: use oauth token instead with Oauth sign in */
    private ArgumentListBuilder dockerLoginCommand() {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("docker").add("login").add("-u").add(getDescriptor().getUserName()).add("-e").add(getDescriptor().getEmail()).add("-p").addMasked(getDescriptor().getPassword());
        if (getDescriptor().getRegistryUrl() != null && !getDescriptor().getRegistryUrl().trim().isEmpty()) {
            args.add(getDescriptor().getRegistryUrl());
        }
        return args;
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
                    maybeLogin() &&
                    isSkipBuild() ? maybeTagOnly() : buildAndTag() &&
                    isSkipPush() ? true : dockerPushCommand();

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
            	tags.add(expandAll(repoName));
            } else {
            	for (String rt: expandAll(getRepoTag()).trim().split(",")) {
            		tags.add(expandAll(repoName + ":" + rt));
            	}
            	if (!isSkipTagLatest()) {
            		tags.add(expandAll(repoName + ":latest"));
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
            		result.add("docker tag " + getRepoName() + " " + tag);
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
			Pattern p = Pattern.compile(".*?Successfully built ([0-9a-z]*).*?");
			Matcher m = p.matcher(lastResult.stdout);
			String image = m.find() ? m.group(1) : null;
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
    	
        private boolean maybeLogin() throws IOException, InterruptedException {
            if (!defined(getDescriptor().getPassword())) {
                listener.getLogger().println("No credentials provided, so not logging in to the registry.");
                return true;
            } else {
                return executeCmd(dockerLoginCommand());
            }
        }

        private boolean executeCmd(ArgumentListBuilder args) throws IOException, InterruptedException {
            return launcher.launch()
                .envs(build.getEnvironment(listener))
                .pwd(build.getWorkspace())
                .stdout(listener.getLogger())
                .stderr(listener.getLogger())
                .cmds(args)
                .start().join() == 0;
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
            PrintStream stdout = listener.getLogger();
            PrintStream stderr = listener.getLogger();

            boolean result = launcher.launch()
                    .envs(build.getEnvironment(listener))
                    .pwd(build.getWorkspace())
                    .stdout(stdout)
                    .stderr(stderr)
                    .cmdAsSingleString(cmd)
                    .start().join() == 0;
            String stdoutStr = stdout.toString();
            return new Result(result, stdoutStr);
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
        public String getEmail() { return email; }
        public String getRegistryUrl() { return registryUrl; }


        private String userName;
        private String password;
        private String email;


        private String registryUrl;


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
            return "CloudBees Docker Build and Publish";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            // To persist global configuration information,
            // set that to properties and call save().
            userName = formData.getString("userName");
            password = formData.getString("password");
            email = formData.getString("email");
            registryUrl = formData.getString("registryUrl");
            save();
            return super.configure(req,formData);
        }


    }
}

