/*
 * Copyright (C) 2015-2018 jomp16 <root@rwx.ovh>
 *
 * This file is part of plugin_manager.
 *
 * plugin_manager is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * plugin_manager is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with plugin_manager. If not, see <http://www.gnu.org/licenses/>.
 */

package ovh.rwx.utils.plugin.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import net.engio.mbassy.bus.MBassador
import net.engio.mbassy.bus.error.IPublicationErrorHandler
import org.reflections.Reflections
import org.reflections.util.ClasspathHelper
import org.reflections.util.ConfigurationBuilder
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ovh.rwx.utils.plugin.api.PluginListener
import ovh.rwx.utils.plugin.event.events.PluginListenerAddedEvent
import ovh.rwx.utils.plugin.event.events.PluginListenerRemovedEvent
import ovh.rwx.utils.plugin.json.PluginInfo
import java.io.File
import java.net.URL
import java.net.URLClassLoader

class PluginManager : AutoCloseable {
    private val log: Logger = LoggerFactory.getLogger(javaClass)
    val pluginsListener: MutableList<Pair<ClassLoader, PluginListener>> = mutableListOf()
    val pluginsJar: MutableMap<String, Triple<PluginInfo, URLClassLoader, List<PluginListener>>> = mutableMapOf()
    private val eventBus = MBassador<Any>(IPublicationErrorHandler { error -> log.error("An error happened when handling listener!", error) })
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    fun loadPluginsFromDir(fileDir: File) {
        if (!fileDir.exists() || !fileDir.isDirectory) return

        log.info("Loading plugins from dir ${fileDir.name}...")

        fileDir.walk().filter { it.isFile && !it.absolutePath.contains("lib") && it.extension == "jar" }.forEach { pluginFile ->
            addPluginJar(pluginFile)
        }
    }

    fun addPluginJar(pluginFile: File): Boolean {
        val pluginPair = loadPluginListenersFromJar(pluginFile)
        val pluginPropertiesStream = pluginPair.first.getResourceAsStream("plugin.json")

        if (pluginPropertiesStream == null) {
            log.error("Plugin ${pluginFile.name} didn't have a plugin.json!")

            return false
        }
        val pluginInfo: PluginInfo = objectMapper.readValue(pluginPropertiesStream)

        pluginPropertiesStream.close()

        if (pluginsJar.containsKey(pluginInfo.name)) return false

        try {
            pluginPair.second.forEach { addPlugin(it, pluginPair.first) }
            pluginsJar[pluginInfo.name] = Triple(pluginInfo, pluginPair.first, pluginPair.second)

            log.info("Loaded plugin: $pluginInfo")

            return true
        } catch (e: Exception) {
            log.error("Failed to load plugin $pluginInfo!", e)

            // Close the URLClassLoader of this plugin jar
            pluginPair.first.close()
        }

        return false
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun addPlugin(pluginListener: PluginListener, classLoader: ClassLoader = javaClass.classLoader) {
        if (pluginsListener.map { it.second }.any { it == pluginListener }) return

        pluginListener.onCreate()

        pluginsListener.add(classLoader to pluginListener)
        eventBus.subscribe(pluginListener)

        executeEvent(PluginListenerAddedEvent(pluginListener))

        log.trace("${pluginListener.javaClass.simpleName} subscribed!")
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun removePlugin(pluginListener: PluginListener) {
        if (!pluginsListener.map { it.second }.any { it == pluginListener }) return

        pluginListener.onDestroy()

        eventBus.unsubscribe(pluginListener)
        pluginsListener.removeAll { it.second == pluginListener }

        executeEvent(PluginListenerRemovedEvent(pluginListener))

        log.trace("${pluginListener.javaClass.simpleName} unsubscribed!")
    }

    fun removePluginJarByName(pluginName: String): Boolean {
        if (!pluginsJar.containsKey(pluginName)) return false

        pluginsJar.remove(pluginName)?.let {
            it.third.forEach { pluginListener -> removePlugin(pluginListener) }

            it.second.close()
        }

        return true
    }

    fun executeEventAsync(eventClass: Any) {
        eventBus.publishAsync(eventClass)
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun executeEvent(eventClass: Any) {
        eventBus.publish(eventClass)
    }

    fun loadPlugins() {
        val reflections = Reflections(ConfigurationBuilder().addUrls(ClasspathHelper.forClassLoader()).addUrls(ClasspathHelper.forManifest()))
        val pluginListenerClasses = reflections.getSubTypesOf(PluginListener::class.java)

        pluginListenerClasses.map { it.getConstructor().newInstance() }.forEach {
            try {
                addPlugin(it, it.javaClass.classLoader)
            } catch (e: Exception) {
                log.error("Failed to load plugin ${it.javaClass.simpleName}!", e)
            }
        }
    }

    private fun loadPluginListenersFromJar(jarFile: File): Pair<URLClassLoader, List<PluginListener>> {
        val pluginListeners: MutableList<PluginListener> = mutableListOf()
        val urlClassLoader = URLClassLoader(arrayOf<URL>(jarFile.toURI().toURL()), ClassLoader.getSystemClassLoader())
        val reflections = Reflections(ConfigurationBuilder().addUrls(URL("file:" + jarFile.path)).addClassLoader(urlClassLoader))
        val pluginListenerClasses = reflections.getSubTypesOf(PluginListener::class.java)

        pluginListenerClasses.map { it.getConstructor() }.mapTo(pluginListeners) { it.newInstance() }

        return urlClassLoader to pluginListeners
    }

    override fun close() {
        log.info("Closing plugin manager...")

        pluginsJar.values.flatMap { it.third }.forEach { removePlugin(it) }
        pluginsJar.values.map { it.second }.toSet().forEach { it.close() }
        pluginsJar.clear()

        pluginsListener.map { it.second }.forEach { removePlugin(it) }
        pluginsListener.clear()

        log.info("Closed plugin manager!")
    }
}