/*
* Copyright 2014 John Ahlroos
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package fi.jasoft.plugin

import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.ProjectEvaluationListener
import org.gradle.api.ProjectState
import org.gradle.api.file.FileTree
import org.gradle.api.plugins.WarPluginConvention

class DependencyListener implements ProjectEvaluationListener {

    /**
     * Added configurations to project
     */
    static enum Configuration {
        SERVER('vaadin'),
        CLIENT('vaadin-client'),
        TESTBENCH('vaadin-testbench'),
        JETTY8('jetty8'),
        PUSH('vaadin-push'),
        JAVADOC('vaadin-javadoc');

        final private String caption

        public Configuration(String caption) { this.caption = caption }

        public caption() { return caption }
    }

    /**
     * Added repositories to project
     */
    static enum Repositories {
        ADDONS('Vaadin addons', 'http://maven.vaadin.com/vaadin-addons'),
        SNAPSHOTS('Vaadin snapshots', 'http://oss.sonatype.org/content/repositories/vaadin-snapshots'),
        JASOFT('Jasoft.fi Maven repository', 'http://mvn.jasoft.fi/maven2')

        final private String caption
        final private String url

        Repositories(String caption, String url) { this.caption = caption; this.url = url }

        public caption() { return caption }

        public url() { return url }
    }

    void beforeEvaluate(Project project) {

        // Check to see if we are using the eclipse plugin instead of the eclipse-wtp plugin
        if (project.plugins.findPlugin('eclipse') && !project.plugins.findPlugin('eclipse-wtp')) {
            project.getLogger().warn("You are using the eclipse plugin which does not support all " +
                    "features of the Vaadin plugin. Please use the eclipse-wtp plugin instead.")
        }
    }

    void afterEvaluate(Project project, ProjectState state) {

        if (!project.hasProperty('vaadin') || !project.vaadin.manageDependencies) {
            return
        }

        String version = project.vaadin.version

        if(version !=null && version.startsWith("6")){
            project.logger.error("Plugin no longer supports Vaadin 6, to use Vaadin 6 apply an older version of the plugin.")
            throw new InvalidUserDataException("Unsupported Vaadin version.")
        }

        // Add repositories unless specified otherwise
        if (project.vaadin.manageRepositories) {
            addRepositories(project)
        }

        createJetty8Configuration(project)

        createVaadin7Configuration(project, version)

        createJavadocConfiguration(project, version)

        if (project.vaadin.testbench.enabled) {
            createTestbenchConfiguration(project)
        }
    }

    private static void addRepositories(Project project) {

        def gradleVersion = project.getGradle().getGradleVersion().split("\\.")
        def gradleMajorVersion = Integer.parseInt(gradleVersion[0])
        def gradleMinorVersion =  Integer.parseInt(gradleVersion[1])

        def repositories = project.repositories

        // Ensure maven central and maven local are included
        repositories.mavenCentral()
        repositories.mavenLocal()

        // Add repositories
        Repositories.values().each { repository ->
            if (repositories.findByName(repository.caption()) == null) {
                if(gradleMinorVersion < 9){
                    repositories.mavenRepo(
                            name: repository.caption(),
                            url: repository.url()
                    )
                }  else {
                    repositories.maven({
                        name = repository.caption()
                        url = repository.url()
                    })
                }
            }
        }

        // Add plugin development repository if specified
        if (new File(GradleVaadinPlugin.getDebugDir()).exists()
                && repositories.findByName('Gradle Vaadin plugin development repository') == null) {

            if (GradleVaadinPlugin.isFirstPlugin()) {
                project.logger.lifecycle("Using development libs found at " + GradleVaadinPlugin.getDebugDir())
            }

            repositories.flatDir(name: 'Gradle Vaadin plugin development repository', dirs: GradleVaadinPlugin.getDebugDir())
        }
        
        repositories.each { repository ->
        	project.logger.lifecycle("Vaadin plugin repository: " + repository)
        }
    }

    private static void createJetty8Configuration(Project project) {
        def conf = Configuration.JETTY8.caption()
        def dependencies = project.dependencies
        if (!project.configurations.hasProperty(conf)) {
            project.configurations.create(conf)
            dependencies.add(conf, 'org.eclipse.jetty.aggregate:jetty-all-server:8.1.15.v20140411')
            dependencies.add(conf, 'fi.jasoft.plugin:gradle-vaadin-plugin:' + GradleVaadinPlugin.getVersion())
            dependencies.add(conf, 'asm:asm-all:3.3.1')
            dependencies.add(conf, 'javax.servlet.jsp:jsp-api:2.2')
        }
    }

    private static void createCommonVaadinConfiguration(Project project) {
        createGWTConfiguration(project)

        def serverConf = Configuration.SERVER.caption()
        def jetty8Conf = Configuration.JETTY8.caption()

        if (!project.configurations.hasProperty(serverConf)) {
            project.configurations.create(serverConf)

            def sources = project.sourceSets.main
            def testSources = project.sourceSets.test

            sources.compileClasspath += project.configurations[serverConf]
            testSources.compileClasspath += project.configurations[serverConf]
            testSources.runtimeClasspath += project.configurations[serverConf]

            // For servlet 3 support
            sources.compileClasspath += project.configurations[jetty8Conf]
            testSources.compileClasspath += project.configurations[jetty8Conf]
            testSources.runtimeClasspath += project.configurations[jetty8Conf]

            // Add server libs to war
            project.war.classpath(project.configurations[serverConf])
        }
    }

    /**
     * Creates the configuration for generating Javadoc
     */
    private static void createJavadocConfiguration(Project project, String version) {
        def javadocConf = Configuration.JAVADOC.caption()
        def dependencies = project.dependencies
        if (!project.configurations.hasProperty(javadocConf)) {
            project.configurations.create(javadocConf)
            dependencies.add(javadocConf, 'javax.portlet:portlet-api:2.0')
            dependencies.add(javadocConf, 'javax.servlet:javax.servlet-api:3.0.1')
            dependencies.add(javadocConf, "com.vaadin:vaadin-push:${version}")
        }
    }

    private static void createVaadin6Configuration(Project project, String version, String gwtVersion) {
        createCommonVaadinConfiguration(project)

        def serverConf = Configuration.SERVER.caption()
        def clientConf = Configuration.CLIENT.caption()
        def dependencies = project.dependencies

        dependencies.add(serverConf, "com.vaadin:vaadin:${version}")

        if (project.vaadin.widgetset != null) {
            dependencies.add(clientConf, "com.google.gwt:gwt-user:" + gwtVersion)
            dependencies.add(clientConf, "com.google.gwt:gwt-dev:" + gwtVersion)
            dependencies.add(clientConf, "javax.validation:validation-api:1.0.0.GA")
        }
    }

    private static void createVaadin7Configuration(Project project, String version) {

        // Create common configuration for both Vaadin 6 and Vaadin 7
        createCommonVaadinConfiguration(project)

        def serverConf = Configuration.SERVER.caption()
        def clientConf = Configuration.CLIENT.caption()
        def dependencies = project.dependencies

        // Theme compiler
        if(!Util.isSassCompilerSupported(project)){
            File webAppDir = project.convention.getPlugin(WarPluginConvention).webAppDir
            FileTree themes = project.fileTree(dir: webAppDir.canonicalPath + '/VAADIN/themes', include: '**/styles.scss')
            if (!themes.isEmpty()) {
                dependencies.add(serverConf, "com.vaadin:vaadin-theme-compiler:${version}")
            }
        }

        // Client compiler or pre-compiled theme
        if (project.vaadin.widgetset == null) {
            dependencies.add(serverConf, "com.vaadin:vaadin-client-compiled:${version}")
        } else {
            dependencies.add(clientConf, "com.vaadin:vaadin-client-compiler:${version}", {

                // Project already has jetty, no need for it to be included again
                exclude([group: 'org.mortbay.jetty'])
            })

            dependencies.add(clientConf, "com.vaadin:vaadin-client:${version}")
            dependencies.add(clientConf, "javax.validation:validation-api:1.0.0.GA")
        }

        // Server
        dependencies.add(serverConf, "com.vaadin:vaadin-server:${version}")

        // Themes
        dependencies.add(serverConf, "com.vaadin:vaadin-themes:${version}")

        // Optional push
        if (Util.isPushSupportedAndEnabled(project)) {
            createPushConfiguration(project, version)
        }
    }

    private static void createGWTConfiguration(Project project) {
        def conf = Configuration.CLIENT.caption()
        def sources = project.sourceSets.main
        def testSources = project.sourceSets.test

        if (!project.configurations.hasProperty(conf)) {
            project.configurations.create(conf)

            sources.compileClasspath += project.configurations[conf]
            testSources.compileClasspath += project.configurations[conf]
            testSources.runtimeClasspath += project.configurations[conf]
        }
    }

    private static void createTestbenchConfiguration(Project project) {
        def conf = Configuration.TESTBENCH.caption()
        def testSources = project.sourceSets.test

        if (!project.configurations.hasProperty(conf)) {
            project.configurations.create(conf)
            project.dependencies.add(conf, "com.vaadin:vaadin-testbench:${project.vaadin.testbench.version}")

            testSources.compileClasspath += project.configurations[conf]
            testSources.runtimeClasspath += project.configurations[conf]
        }
    }

    private static void createPushConfiguration(Project project, String version) {
        def conf = Configuration.PUSH.caption()
        def sources = project.sourceSets.main
        def testSources = project.sourceSets.test

        if (!project.configurations.hasProperty(conf)) {
            project.configurations.create(conf)
            project.dependencies.add(conf, "com.vaadin:vaadin-push:${version}")

            sources.compileClasspath += project.configurations[conf]
            testSources.compileClasspath += project.configurations[conf]
            testSources.runtimeClasspath += project.configurations[conf]
        }
    }
}