/*
 * The MIT License
 *
 * Copyright (c) 2015 CloudBees, Inc.
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

import java.io.IOException;
import javax.annotation.CheckForNull;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Retrieves the data for {@link DockerBuilder} from docker-java datatypes
 * @author Oleg Nenashev
 * @since TODO
 */
@Restricted(NoExternalUse.class)
public class DockerCLIHelper {
    
    /**
     * Retrieves an info about image from command-line outputs.
     * @param stdout Data output to be parsed
     * @return ImageId or null
     * @throws IOException Cannot parse the response
     * @since TODO
     */
    @CheckForNull
    public static InspectImageResponse parseInspectImageResponse(String stdout) throws IOException {
        JSONArray array = JSONArray.fromObject(stdout);
        if (array != null && array.size() > 0) {
            return new InspectImageResponse(array.getJSONObject(0));
        } else {
            return null;
        }     
    }

    @Restricted(NoExternalUse.class)
    public static class InspectImageResponse {

        private final String parent;
        private final String id;

        public InspectImageResponse(JSONObject inspectImageResponse) throws IOException {
            this.parent = inspectImageResponse.getString("Parent");
            this.id = inspectImageResponse.getString("Id");
        }
        
        public InspectImageResponse(String parent, String id) {
            this.parent = parent;
            this.id = id;
        }

        public String getParent() {
            return parent;
        }

        public String getId() {
            return id;
        }
    }
}
