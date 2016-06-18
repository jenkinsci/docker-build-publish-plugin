/*
 * The MIT License
 *
 * Copyright (c) 2015, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.cloudbees.dockerpublish;

import static org.junit.Assert.*;

import hudson.model.FreeStyleProject;
import hudson.model.Items;

import java.net.URL;

import org.jenkinsci.plugins.docker.commons.credentials.DockerServerEndpoint;
import org.junit.Rule;
import org.jvnet.hudson.test.JenkinsRule;

import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;
import org.junit.Test;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;

/**
 * @author Carlos Sanchez <carlos@apache.org>
 */
public class DockerBuilderTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void testReadResolve() throws Exception {
        assertRegistry("https://index.docker.io/v1/", "acme/test", "acme/test");
        assertRegistry("https://index.docker.io/v1/", "busybox", "busybox");
        assertRegistry("https://docker.acme.com:8080", "acme/test", "docker.acme.com:8080/acme/test");
        assertRegistry("https://docker.acme.com", "acme/test", "docker.acme.com/acme/test");
        assertRegistry("https://docker.acme.com", "busybox", "docker.acme.com/busybox");
    }

    private void assertRegistry(String url, String image, String repo) throws Exception {
        DockerBuilder original = new DockerBuilder(repo);
        original.setRegistry(null);

        DockerBuilder builder = (DockerBuilder) Items.XSTREAM2.fromXML(Items.XSTREAM2.toXML(original));
        DockerRegistryEndpoint registry = builder.getRegistry();
        assertEquals(url, registry.getEffectiveUrl().toString());
        assertEquals(image, builder.getRepoName());
    }

    @Test
    public void testGetImageBuiltFromStdout() throws Exception {
        URL url;
        String image;

        url = Resources.getResource("docker-build-stdout-1.txt");
        image = DockerBuilder.getImageBuiltFromStdout(Resources.toString(url, Charsets.UTF_8));
        assertEquals("5b4f9edeb8d4", image);

        url = Resources.getResource("docker-build-stdout-2.txt");
        image = DockerBuilder.getImageBuiltFromStdout(Resources.toString(url, Charsets.UTF_8));
        assertEquals("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef", image);

        url = Resources.getResource("docker-build-stdout-3.txt");
        image = DockerBuilder.getImageBuiltFromStdout(Resources.toString(url, Charsets.UTF_8));
        assertEquals("cd2a98e19492", image);
    }

    @Test
    public void testRoundTrip() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();

        DockerBuilder before = new DockerBuilder("example/test");

        before.setBuildContext("./directory`");
        before.setCreateFingerprint(true);
        before.setDockerfilePath("Dockerfile.custom");
        before.setForcePull(true);
        before.setNoCache(true);
        before.setRegistry(new DockerRegistryEndpoint(null,null));
        before.setRepoTag("12345");
        before.setServer(new DockerServerEndpoint(null, null));
        before.setSkipBuild(true);
        before.setSkipDecorate(true);
        before.setSkipPush(true);
        before.setSkipTagLatest(true);

        project.getBuildersList().add(before);

        jenkins.submit(
            jenkins.createWebClient()
                .getPage(project, "configure")
                .getFormByName("config"));

        DockerBuilder after = project.getBuildersList().get(DockerBuilder.class);

        jenkins.assertEqualDataBoundBeans(before, after);
    }

    @Test
    public void testOptionalFieldsAreNulled() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();

        DockerBuilder before = new DockerBuilder("example/test");

        before.setBuildContext("");
        before.setDockerfilePath("");

        project.getBuildersList().add(before);

        jenkins.submit(
            jenkins.createWebClient()
                .getPage(project, "configure")
                .getFormByName("config"));

        DockerBuilder after = project.getBuildersList().get(DockerBuilder.class);

        assertEquals(null, after.getBuildContext());
        assertEquals(null,after.getDockerfilePath());
    }
}
