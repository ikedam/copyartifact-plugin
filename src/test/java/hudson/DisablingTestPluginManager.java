/*
 * The MIT License
 * 
 * Copyright (c) 2014 IKEDA Yasuyuki
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

// placed here to access PluginManager#loadBundledPlugins
package hudson;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.io.FilenameUtils;
import org.jvnet.hudson.test.HudsonTestCase;
import org.jvnet.hudson.test.TestPluginManager;

/**
 * PluginManager to remove disabled plugins for testing purpose.
 */
public class DisablingTestPluginManager extends PluginManager
{
    private static final Logger LOGGER = Logger.getLogger(HudsonTestCase.class.getName());
    private static final List<String> pluginExtensionList = Arrays.asList("jpi");
    
    private final List<String> pluginsToIgnoreList;
    
    public DisablingTestPluginManager(File rootDir, String[] pluginsToIgnore) {
        super(null, new File(rootDir, "plugins"));
        pluginsToIgnoreList = Arrays.asList(pluginsToIgnore);
    }
    
    /**
     * @return
     * @throws Exception
     * @see hudson.PluginManager#loadBundledPlugins()
     */
    @Override
    protected Collection<String> loadBundledPlugins() throws Exception
    {
        Collection<String> plugins = TestPluginManager.INSTANCE.loadBundledPlugins();
        if (pluginsToIgnoreList != null) {
            for(File pluginFile: rootDir.listFiles(new FilenameFilter() {
                    public boolean accept(File dir, String name) {
                        return pluginExtensionList.contains(FilenameUtils.getExtension(name));
                    }
            })) {
                if (pluginsToIgnoreList.contains(FilenameUtils.getBaseName(pluginFile.getName()))) {
                    LOGGER.log(Level.INFO, "Removed {0}", pluginFile.getAbsolutePath());
                    pluginFile.delete();
                    plugins.remove(pluginFile.getName());
                }
            }
        }
        return plugins;
    }
    
}
