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
package fi.jasoft.plugin.tasks

import fi.jasoft.plugin.TemplateUtil
import fi.jasoft.plugin.Util
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction

import java.util.jar.Attributes
import java.util.jar.JarInputStream
import java.util.jar.Manifest

class UpdateWidgetsetTask extends DefaultTask {

    public static final String NAME = 'vaadinUpdateWidgetset'

    public UpdateWidgetsetTask() {
        description = "Updates the widgetset xml file"
    }

    @TaskAction
    public void run() {
        if (project.vaadin.widgetset == null) {
            return;
        }

        ensureWidgetPresent(project)
    }

    public static boolean ensureWidgetPresent(Project project) {
        if (!project.vaadin.manageWidgetset) {
            return false;
        }

        // Check source dir if widgetset is present there
        File javaDir = Util.getMainSourceSet(project).srcDirs.iterator().next()
        File widgetsetFile = new File(javaDir.canonicalPath + '/' + project.vaadin.widgetset.replaceAll(/\./, '/') + ".gwt.xml")

        if (widgetsetFile.exists()) {
            updateWidgetset(widgetsetFile, project);
            return false;
        }

        // Check resource dir if widgetset is present there
        File resourceDir = project.sourceSets.main.resources.srcDirs.iterator().next()
        widgetsetFile = new File(resourceDir.canonicalPath + '/' + project.vaadin.widgetset.replaceAll(/\./, '/') + ".gwt.xml")

        if (widgetsetFile.exists()) {
            updateWidgetset(widgetsetFile, project);
            return false;
        }

        // No widgetset detected, create one
        new File(widgetsetFile.parent).mkdirs()

        widgetsetFile.createNewFile()

        updateWidgetset(widgetsetFile, project);

        return true;
    }

    private static void updateWidgetset(File widgetsetFile, Project project) {
        def substitutions = [:]

        def inherits = ['com.vaadin.DefaultWidgetSet']

        // Scan classpath for Vaadin addons and inherit their widgetsets
        project.configurations.compile.each {
            JarInputStream jarStream = new JarInputStream(it.newDataInputStream());
            Manifest mf = jarStream.getManifest();
            if (mf != null) {
                Attributes attributes = mf.getMainAttributes()
                if (attributes != null) {
                    String widgetsets = attributes.getValue('Vaadin-Widgetsets')
                    if (widgetsets != null) {
                        for (String widgetset : widgetsets.split(",")) {
                            if (widgetset != 'com.vaadin.terminal.gwt.DefaultWidgetSet'
                                    && widgetset != 'com.vaadin.DefaultWidgetSet') {
                                inherits.push(widgetset)
                            }
                        }
                    }
                }
            }
        }

        // Custom inherits
        if (project.vaadin.gwt.extraInherits != null) {
            inherits.addAll(project.vaadin.gwt.extraInherits)
        }

        substitutions['inherits'] = inherits

        //###################################################################

        substitutions['sourcePaths'] = project.vaadin.gwt.sourcePaths

        //###################################################################

        def configurationProperties = [:]
        if (project.vaadin.devmode.superDevMode) {
            configurationProperties['devModeRedirectEnabled'] = true
        }

        substitutions['configurationProperties'] = configurationProperties

        //###################################################################

        def properties = [:]

        def ua = 'ie8,ie9,gecko1_8,safari,opera'
        if (project.vaadin.gwt.userAgent == null) {
            if (Util.isIE10UserAgentSupported(project)) {
                ua += ',ie10'
            }
        } else {
            ua = project.vaadin.gwt.userAgent
        }
        properties.put('user.agent', ua)

        if (project.vaadin.profiler) {
            properties.put('vaadin.profiler', true)
        }

        if (!project.vaadin.gwt.logging) {
            properties.put('gwt.logging.enabled', false)
        }

        substitutions['properties'] = properties

        //###################################################################

        String name, pkg, filename
        if (project.vaadin.widgetsetGenerator == null) {
            name = project.vaadin.widgetset.tokenize('.').last()
            pkg = project.vaadin.widgetset.replaceAll('.' + name, '') + '.client.ui'
            filename = name + "Generator.java"

        } else {
            name = project.vaadin.widgetsetGenerator.tokenize('.').last()
            pkg = project.vaadin.widgetsetGenerator.replaceAll('.' + name, '')
            filename = name + ".java"
        }

        File javaDir = Util.getMainSourceSet(project).srcDirs.iterator().next()
        File f = new File(javaDir.canonicalPath + '/' + pkg.replaceAll(/\./, '/') + '/' + filename)

        if (f.exists() || project.vaadin.widgetsetGenerator != null) {
            substitutions['widgetsetGenerator'] = "${pkg}.${filename.replaceAll('.java$', '')}"
        }

        //###################################################################

        def linkers = [:]

        File[] clientSCSS = TemplateUtil.getFilesFromPublicFolder(project, 'scss')
        if (clientSCSS.length > 0) {
            linkers.put('scssintegration', 'com.vaadin.sass.linker.SassLinker')
        }

        substitutions['linkers'] = linkers

        //###################################################################

        def stylesheets = []

        clientSCSS.each {

            // Get the relative path to the public folder
            String path = it.parent.substring(it.parent.lastIndexOf('public') + 'public'.length())
            if (path.startsWith('/')) {
                path = path.substring(1);
            }

            // Convert file name from scss -> css
            filename = it.name.substring(0, it.name.length() - 4) + 'css'

            // Recreate relative path to scss file
            path = path + '/' + filename
            if (path.startsWith('/')) {
                path = path.substring(1);
            }

            stylesheets.add(path)
        }

        // Retrive CSS files
        File[] clientCSS = TemplateUtil.getFilesFromPublicFolder(project, 'css')
        clientCSS.each {

            // Get the relative path to the public folder
            String path = it.parent.substring(it.parent.lastIndexOf('public') + 'public'.length())
            if (path.startsWith('/')) {
                path = path.substring(1);
            }

            // Recreate relative path to css file
            path = path + '/' + it.name
            if (path.startsWith('/')) {
                path = path.substring(1);
            }

            stylesheets.add(path)
        }

        substitutions['stylesheets'] = stylesheets

        //###################################################################

        substitutions['collapsePermutations'] = project.vaadin.gwt.collapsePermutations

        //###################################################################

        // Write widgetset file
        File widgetsetDir = new File(widgetsetFile.parent)
        String moduleXML = project.vaadin.widgetset.tokenize('.').last() + ".gwt.xml"
        TemplateUtil.writeTemplate('Widgetset.xml', widgetsetDir, moduleXML, substitutions, true)
    }
}