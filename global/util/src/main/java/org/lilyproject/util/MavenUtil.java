package org.lilyproject.util;

import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class MavenUtil {
    public static File findLocalMavenRepository() throws IOException {
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
                throw new IOException("Error reading Maven settings file at " + mavenSettingsFile.getAbsolutePath(), e);
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
