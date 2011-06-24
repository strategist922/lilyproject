/*
 * Copyright 2010 Outerthought bvba
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lilyproject.solrtestfw;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.input.NullInputStream;
import org.lilyproject.util.MavenUtil;
import org.lilyproject.util.test.TestHomeUtil;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.webapp.WebAppContext;

import java.io.*;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SolrTestingUtility {
    private int solrPort = 8983;
    private Server server;
    private String schemaLocation;
    private String autoCommitSetting;
    private String solrWarPath;
    private File solrHomeDir;
    private File solrConfDir;

    public SolrTestingUtility() throws IOException {
        this(null);
    }

    public SolrTestingUtility(File solrHomeDir) throws IOException {
        if (solrHomeDir == null) {
            this.solrHomeDir = TestHomeUtil.createTestHome("lily-solrtesthome-");
        } else {
            this.solrHomeDir = solrHomeDir;
        }
    }

    public String getSchemaLocation() {
        return schemaLocation;
    }

    public void setSchemaLocation(String schemaLocation) {
        this.schemaLocation = schemaLocation;
    }

    public String getAutoCommitSetting() {
        return autoCommitSetting;
    }

    public void setAutoCommitSetting(String autoCommitSetting) {
        this.autoCommitSetting = autoCommitSetting;
    }

    public String getSolrWarPath() {
        return solrWarPath;
    }

    public void setSolrWarPath(String solrWarPath) {
        this.solrWarPath = solrWarPath;
    }

    public void start() throws Exception {
        solrConfDir = new File(solrHomeDir, "conf");
        FileUtils.forceMkdir(solrConfDir);

        copyDefaultConfigToSolrHome(autoCommitSetting == null ? "" : autoCommitSetting);

        if (schemaLocation != null) {
            if (schemaLocation.startsWith("classpath:")) {
                copySchemaFromResource(schemaLocation.substring("classpath:".length()));
            } else {
                copySchemaFromFile(new File(schemaLocation));
            }
        } else {
            copySchemaFromResource("org/lilyproject/solrtestfw/conftemplate/schema.xml");
        }

        setSystemProperties();


        // Determine location of Solr war file:
        //  - either provided by setSolrWarPath()
        //  - or provided via system property solr.war
        //  - finally use default, assuming availability in local repository
        if (solrWarPath == null) {
            solrWarPath = System.getProperty("solr.war");
        }
        if (solrWarPath == null) {
            Properties properties = new Properties();
            InputStream is = getClass().getResourceAsStream("solr.properties");
            if (is != null) {
                properties.load(is);
                is.close();
                String solrVersion = properties.getProperty("solr.version");
                solrWarPath = MavenUtil.findLocalMavenRepository().getAbsolutePath() +
                        "/org/apache/solr/solr-webapp/" + solrVersion + "/solr-webapp-" + solrVersion + ".war";
            }
        }

        if (solrWarPath == null || !new File(solrWarPath).exists()) {
            System.out.println();
            System.out.println("------------------------------------------------------------------------");
            System.out.println("Solr not found at");
            System.out.println(solrWarPath);
            System.out.println("------------------------------------------------------------------------");
            System.out.println();
            throw new Exception("Solr war not found at " + solrWarPath);
        }

        server = new Server(solrPort);
        server.addHandler(new WebAppContext(solrWarPath, "/solr"));

        server.start();
    }

    public String getUri() {
        return "http://localhost:" + solrPort + "/solr";
    }

    public Server getServer() {
        return server;
    }

    public void stop() throws Exception {
        if (server != null)
            server.stop();

        if (solrHomeDir != null) {
            FileUtils.deleteDirectory(solrHomeDir);
        }
    }

    public void setSystemProperties() {
        System.setProperty("solr.solr.home", solrHomeDir.getAbsolutePath());
        System.setProperty("solr.data.dir", new File(solrHomeDir, "data").getAbsolutePath());
    }

    public void copyDefaultConfigToSolrHome(String autoCommitSetting) throws IOException {
        copyResourceFiltered("org/lilyproject/solrtestfw/conftemplate/solrconfig.xml",
                new File(solrConfDir, "solrconfig.xml"), autoCommitSetting);
        createEmptyFile(new File(solrConfDir, "synonyms.txt"));
        createEmptyFile(new File(solrConfDir, "stopwords.txt"));
        createEmptyFile(new File(solrConfDir, "protwords.txt"));
    }

    public void copySchemaFromFile(File schemaFile) throws IOException {
        FileUtils.copyFile(schemaFile, new File(solrConfDir, "schema.xml"));
    }

    public void copySchemaFromResource(String path) throws IOException {
        copyResource(path, new File(solrConfDir, "schema.xml"));
    }

    private void copyResource(String path, File destination) throws IOException {
        InputStream is = getClass().getClassLoader().getResourceAsStream(path);
        FileUtils.copyInputStreamToFile(is, destination);
        is.close();
    }

    private void copyResourceFiltered(String path, File destination, String autoCommitSetting) throws IOException {

        InputStream is = getClass().getClassLoader().getResourceAsStream(path);
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));

        FileWriter writer = new FileWriter(destination);

        String placeholder = Pattern.quote("<!--AUTOCOMMIT_PLACEHOLDER-->");
        String replacement = Matcher.quoteReplacement(autoCommitSetting);

        String line;
        while ((line = reader.readLine()) != null) {
            line = line.replaceAll(placeholder, replacement);
            writer.write(line);
            writer.write('\n');
        }

        reader.close();
        writer.close();
    }

    private void createEmptyFile(File destination) throws IOException {
        FileUtils.copyInputStreamToFile(new NullInputStream(0), destination);
    }
}
