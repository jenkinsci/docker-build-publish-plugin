package com.cloudbees.dockerpublish;

import static org.junit.Assert.*;

import java.net.URL;

import org.junit.Test;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;

public class DockerBuilderTest {

    @Test
    public void testGetImageBuiltFromStdout() throws Exception {
        URL url = Resources.getResource("docker-build-stdout.txt");
        String image = DockerBuilder.getImageBuiltFromStdout(Resources.toString(url, Charsets.UTF_8));
        assertEquals("5b4f9edeb8d4", image);
    }

}
