/*
 * Copyright 2008 Outerthought bvba and Schaubroeck nv
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
package org.lilyproject.runtime.launcher;

import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import javax.xml.namespace.NamespaceContext;
import javax.xml.XMLConstants;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;

/**
 * This class is simply an indirect way to call the main method of the
 * LilyRuntimeCli, before doing so it creates a classloader from
 * which to load Lily Runtime.
 *
 * <p>It is also smart enough to find your local Maven repository, if
 * you don't specify another location to launch it from using
 * -Dlilyruntime.launcher.repository
 */
public class LilyRuntimeCliLauncher {
    private File repositoryLocation;

    public static void main(String[] args) throws Throwable {
        File repositoryLocation;
        String param = System.getProperty("lilyruntime.launcher.repository");
        if (param != null) {
            repositoryLocation = new File(param);
        } else {
            repositoryLocation = findLocalMavenRepository();
        }

        launch(repositoryLocation, args);
    }

    public static void launch(File repositoryLocation, String[] args) throws Throwable {
        new LilyRuntimeCliLauncher(repositoryLocation).run(args);
    }

    private LilyRuntimeCliLauncher(File repositoryLocation) {
        this.repositoryLocation = repositoryLocation;
    }

    public void run(String[] args) throws Throwable {

        ClassLoader classLoader = LauncherClasspathHelper.getClassLoader("org/lilyproject/runtime/launcher/classloader.xml", repositoryLocation);
        Thread.currentThread().setContextClassLoader(classLoader);

        Method mainMethod;
        try {
            Class runtimeClass = classLoader.loadClass("org.lilyproject.runtime.cli.LilyRuntimeCli");
            mainMethod = runtimeClass.getMethod("main", String[].class);
        } catch (Exception e) {
            throw new RuntimeException("Error loading Lily runtime", e);
        }

        try {
            mainMethod.invoke(null, (Object)args);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Error launching Lily runtime", e);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }

    // This method is duplicated in LilyRuntimeCli, so if you modify it
    // here, it is likely useful to copy you modifications there too.
    private static File findLocalMavenRepository() {
        String homeDir = System.getProperty("user.home");
        File mavenSettingsFile = new File(homeDir + "/.m2/settings.xml");
        if (mavenSettingsFile.exists()) {
            try {
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                dbf.setNamespaceAware(true);
                DocumentBuilder db = dbf.newDocumentBuilder();
                Document document = db.parse(mavenSettingsFile);
                XPath xpath = XPathFactory.newInstance().newXPath();
                SimpleNamespaceContext nc = new SimpleNamespaceContext();
                nc.addPrefix("m", "http://maven.apache.org/POM/4.0.0");
                xpath.setNamespaceContext(nc);

                String localRepository = xpath.evaluate("string(/m:settings/m:localRepository)", document);
                if (localRepository != null && localRepository.length() > 0) {
                    return new File(localRepository);
                }

                // Usage of the POM namespace in settings.xml is optional, so also try without namespace
                localRepository = xpath.evaluate("string(/settings/localRepository)", document);
                if (localRepository != null && localRepository.length() > 0) {
                    return new File(localRepository);
                }
            } catch (Exception e) {
                System.err.println("Error reading Maven settings file at " + mavenSettingsFile.getAbsolutePath());
                e.printStackTrace();
                System.exit(1);
            }
        }
        return new File(homeDir + "/.m2/repository");
    }

    public static class SimpleNamespaceContext implements NamespaceContext {
        private Map<String, String> prefixToUri = new HashMap<String, String>();

        public void addPrefix(String prefix, String uri) {
            prefixToUri.put(prefix, uri);
        }

        public String getNamespaceURI(String prefix) {
            if (prefix == null)
                throw new IllegalArgumentException("Null argument: prefix");

            if (prefix.equals(XMLConstants.XML_NS_PREFIX))
                return XMLConstants.XML_NS_URI;
            else if (prefix.equals(XMLConstants.XMLNS_ATTRIBUTE))
                return XMLConstants.XMLNS_ATTRIBUTE_NS_URI;

            String uri = prefixToUri.get(prefix);
            if (uri != null)
                return uri;
            else
                return XMLConstants.NULL_NS_URI;
        }

        public String getPrefix(String namespaceURI) {
            throw new RuntimeException("Not implemented.");
        }

        public Iterator getPrefixes(String namespaceURI) {
            throw new RuntimeException("Not implemented.");
        }
    }
}