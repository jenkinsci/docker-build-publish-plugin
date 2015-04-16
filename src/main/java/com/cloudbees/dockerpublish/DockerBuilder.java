package com.cloudbees.dockerpublish;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jenkinsci.plugins.docker.commons.KeyMaterial;
import org.jenkinsci.plugins.docker.commons.DockerRegistryEndpoint;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.kohsuke.stapler.DataBoundConstructor;


/**
 * Plugin to build and publish docker projects to the docker registry/index.
 * This can optionally push, and bust cache.
 *
 * @author Michael Neale
 */
public class DockerBuilder extends Builder {

    private final DockerRegistryEndpoint registry;
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
    public DockerBuilder(DockerRegistryEndpoint registry, String repoName, String repoTag, boolean skipPush, boolean noCache, boolean forcePull, boolean skipBuild, boolean skipDecorate, boolean skipTagLatest, String dockerfilePath) {
        this.registry = registry;
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

    public DockerRegistryEndpoint getRegistry() {return registry; }
    public String getRepoName() {return repoName; }
    public String getRepoTag() {  return repoTag; }
    public boolean isSkipPush() { return skipPush;}
    public boolean isSkipBuild() { return skipBuild;}
    public boolean isSkipDecorate() { return skipDecorate;}
    public boolean isSkipTagLatest() { return skipTagLatest;}
    public boolean isNoCache() { return noCache;}
    public boolean isForcePull() { return forcePull;}
    public String getDockerfilePath() { return dockerfilePath; }

    /**
     * Fully qualified repository/image name with the registry url in front
     * @return ie. docker.acme.com/jdoe/busybox
     * @throws IOException
     */
    public String getRepo() throws IOException {
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

            KeyMaterial key = null;
            try {
                // get Docker registry credentials
                key = registry.materialize(build);

                EnvVars env = new EnvVars();
                env.putAll(build.getEnvironment(listener));
                env.putAll(key.env());
    
                boolean result = launcher.launch()
                        .envs(env)
                        .pwd(build.getWorkspace())
                        .stdout(stdout)
                        .stderr(stderr)
                        .cmdAsSingleString(cmd)
                        .start().join() == 0;
                String stdoutStr = stdout.toString();
                return new Result(result, stdoutStr);

            } finally {
                key.close();
            }
        }

        private boolean recordException(Exception e) {
            listener.error(e.getMessage());
            e.printStackTrace(listener.getLogger());
            return false;
        }
    	
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

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
    }

}
