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

package ovh.rwx.utils.plugin.api

import org.slf4j.Logger
import org.slf4j.LoggerFactory

abstract class PluginListener {
    protected val log: Logger = LoggerFactory.getLogger(javaClass)

    open fun onCreate() {
        // ugly hack. Never to do this!
        Thread.currentThread().contextClassLoader = javaClass.classLoader
    }

    open fun onDestroy() {
    }
}