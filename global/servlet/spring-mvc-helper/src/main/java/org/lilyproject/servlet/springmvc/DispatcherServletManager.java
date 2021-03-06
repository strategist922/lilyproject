/*
 * Copyright 2013 NGDATA nv
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
package org.lilyproject.servlet.springmvc;

import javax.annotation.PostConstruct;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import java.util.Collections;
import java.util.EventListener;
import java.util.List;

import org.lilyproject.runtime.rapi.LilyRuntimeModule;
import org.lilyproject.servletregistry.api.ServletRegistry;
import org.lilyproject.servletregistry.api.ServletRegistryEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * This class is part of the mvc project because it is mvc specific. It creates a spring mvc dispatcher servlet
 * which has the lily runtime spring app context as parent app context and registers this servlet with the container.
 */
public class DispatcherServletManager {

    @Autowired
    private LilyRuntimeModule module;

    @Autowired
    private ServletRegistry servletRegistry;

    private List<String> urlPatterns = Collections.emptyList();

    private final String springMvcApplicationContextLocation;

    public DispatcherServletManager(String springMvcApplicationContextLocation) {
        this.springMvcApplicationContextLocation = springMvcApplicationContextLocation;
    }

    @PostConstruct
    public void createAndRegisterDispatcherServletInContainer() {
        final ApplicationContext existingLilyRuntimeSpringContext = module.getSpringContext();

        servletRegistry.addEntry(new ServletRegistryEntry() {
            @Override
            public List<String> getUrlPatterns() {
                return urlPatterns;
            }

            @Override
            public Servlet getServletInstance(ServletContext context) {
                ClassLoader orig = Thread.currentThread().getContextClassLoader();
                Thread.currentThread().setContextClassLoader(module.getClassLoader());

                try {
                    final GenericWebApplicationContext mvcContext = new GenericWebApplicationContext(context);

                    mvcContext.setClassLoader(module.getClassLoader());

                    mvcContext.setServletContext(context);
                    XmlBeanDefinitionReader xmlReader = new XmlBeanDefinitionReader(mvcContext);
                    xmlReader.setBeanClassLoader(module.getClassLoader());
                    xmlReader.loadBeanDefinitions(new ClassPathResource(springMvcApplicationContextLocation,
                            module.getClassLoader()));
                    mvcContext.setParent(existingLilyRuntimeSpringContext);
                    mvcContext.refresh();

                    DispatcherServlet dispatcherServlet = new DispatcherServlet(mvcContext);
                    dispatcherServlet.setDetectAllHandlerMappings(true);
                    return dispatcherServlet;
                } finally {
                    Thread.currentThread().setContextClassLoader(orig);
                }
            }

            @Override
            public List<EventListener> getEventListeners() {
                return Collections.emptyList();
            }
        });
    }

    public void setUrlPatterns(List<String> urlPatterns) {
        this.urlPatterns = urlPatterns;
    }
}
