/*
 * gretty
 *
 * Copyright 2013  Andrey Hihlovskiy.
 *
 * See the file "license.txt" for copying and usage permission.
 */
package org.akhikhl.gretty

import org.apache.catalina.Container
import org.apache.catalina.Context
import org.apache.catalina.Host
import org.apache.catalina.Lifecycle
import org.apache.catalina.LifecycleEvent
import org.apache.catalina.LifecycleListener
import org.apache.catalina.connector.Connector
import org.apache.catalina.core.StandardContext
import org.apache.catalina.loader.WebappLoader
import org.apache.catalina.startup.ContextConfig
import org.apache.catalina.startup.Tomcat
import org.apache.catalina.startup.Tomcat.DefaultWebXmlListener
import org.apache.catalina.startup.Tomcat.FixContextListener
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 *
 * @author ahi
 */
class TomcatServerConfigurer {
  
  protected final Logger log

  TomcatServerConfigurer() {
    log = LoggerFactory.getLogger(this.getClass())
  }
  
  Tomcat createAndConfigureServer(Map params, Closure configureContext = null) {

    Tomcat tomcat = new Tomcat()
    File tempDir = new File(System.getProperty('java.io.tmpdir'), 'tomcat-' + UUID.randomUUID().toString())
    new File(tempDir, 'webapps').mkdirs()
    tempDir.deleteOnExit()
    tomcat.setBaseDir(tempDir.absolutePath)

		tomcat.getHost().setAutoDeploy(true)
		tomcat.getEngine().setBackgroundProcessorDelay(-1)

    if(params.host)
      tomcat.setHostname(params.host)

    if(params.httpPort) {
      final Connector httpConn = new Connector('HTTP/1.1')
      httpConn.setScheme('http')
      httpConn.setPort(params.httpPort)
      httpConn.setProperty('maxPostSize', '0')  // unlimited
      if(params.httpIdleTimeout)
        httpConn.setProperty('keepAliveTimeout', params.httpIdleTimeout)
      if(params.httpsPort)
        httpConn.setRedirectPort(params.httpsPort)
      tomcat.getService().addConnector(httpConn)
      tomcat.setConnector(httpConn)
    }

    if(params.httpsPort) {
      final Connector httpsConn = new Connector('HTTP/1.1')
      httpsConn.setScheme('https')
      httpsConn.setPort(params.httpsPort)
      httpsConn.setProperty('maxPostSize', '0')  // unlimited
      httpsConn.setSecure(true)
      httpsConn.setProperty('SSLEnabled', 'true')
      if(params.sslKeyManagerPassword)
        httpsConn.setProperty('keyPass', params.sslKeyManagerPassword)
      if(params.sslKeyStorePath)
        httpsConn.setProperty('keystoreFile', params.sslKeyStorePath)
      if(params.sslKeyStorePassword)
        httpsConn.setProperty('keystorePass', params.sslKeyStorePassword)
      if(params.sslTrustStorePath)
        httpsConn.setProperty('truststoreFile', params.sslTrustStorePath)
      if(params.sslTrustStorePassword)
        httpsConn.setProperty('truststorePass', params.sslTrustStorePassword)
      if(params.httpsIdleTimeout)
        httpsConn.setProperty('keepAliveTimeout', params.httpsIdleTimeout)
      tomcat.getService().addConnector(httpsConn)
      if(!params.httpPort)
        tomcat.setConnector(httpsConn)
    }

    for(def webapp in params.webApps) {
      StandardContext context = (params.contextClass ? params.contextClass.newInstance() : new StandardContext())
      context.setName(webapp.contextPath)
      context.setPath(webapp.contextPath)
      context.setDocBase(webapp.resourceBase)
      context.addLifecycleListener(new FixContextListener())
      ClassLoader parentClassLoader = params.parentClassLoader ?: this.getClass().getClassLoader()
      ClassLoader classLoader
      if(webapp.webappClassPath) {
        URL[] classpathUrls = (webapp.webappClassPath.collect { new URL(it) }) as URL[]
        classLoader = new URLClassLoader(classpathUrls, parentClassLoader)
        context.addLifecycleListener(new SpringloadedCleanup())
      } else
        classLoader = parentClassLoader
      context.setParentClassLoader(classLoader)
      WebappLoader loader = new WebappLoader(classLoader)
      loader.setLoaderClass(TomcatEmbeddedWebappClassLoader.class.getName())
      loader.setDelegate(true)
      context.setLoader(loader)
      context.addLifecycleListener(new ContextConfig())

      if(configureContext)
        configureContext(webapp, context)
      
      tomcat.getHost().addChild(context)
    }
    
    tomcat
  }
  
  private class SpringloadedCleanup implements LifecycleListener {
    
		@Override
		public void lifecycleEvent(LifecycleEvent event) {
      if(event.getType() == Lifecycle.BEFORE_STOP_EVENT)
        cleanup(event.getLifecycle())
    }
    
    protected void cleanup(StandardContext context) {      
      def TypeRegistry
      try {
        TypeRegistry = Class.forName('org.springsource.loaded.TypeRegistry', true, this.class.getClassLoader())
      } catch(ClassNotFoundException e) {
        // springloaded not present, just ignore
        return
      }
      ClassLoader classLoader = context.getLoader().getClassLoader()
      while(classLoader != null) {
        def typeRegistry = TypeRegistry.getTypeRegistryFor(classLoader)
        if(typeRegistry != null && typeRegistry.@fsWatcher != null) {
          log.info 'springloaded shutdown: {}', typeRegistry.@fsWatcher.@thread
          typeRegistry.@fsWatcher.shutdown()
          typeRegistry.@fsWatcher.@thread.join()
        }
        classLoader = classLoader.getParent()
      }
    }
  }
}
