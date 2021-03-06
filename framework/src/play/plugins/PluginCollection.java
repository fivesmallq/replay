package play.plugins;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.Play;
import play.PlayPlugin;
import play.data.binding.RootParamNode;
import play.db.Model;
import play.inject.Injector;
import play.mvc.Http;
import play.mvc.Router;
import play.mvc.results.Result;
import play.templates.Template;
import play.vfs.VirtualFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.URL;
import java.util.*;

import static java.util.Collections.list;
import static java.util.Objects.hash;
import static java.util.stream.Collectors.toList;

/**
 * Class handling all plugins used by Play.
 *
 * Loading/reloading/enabling/disabling is handled here.
 *
 * This class also exposes many PlayPlugin-methods which when called, the method is executed on all enabled plugins.
 *
 * Since all the enabled-plugins-iteration is done here, the code elsewhere is cleaner.
 */
public class PluginCollection {
    private static final Logger logger = LoggerFactory.getLogger(PluginCollection.class);

    /**
     * List that holds all loaded plugins, enabled or disabled
     */
    protected List<PlayPlugin> allPlugins = new ArrayList<>();

    /**
     * Readonly copy of allPlugins - updated each time allPlugins is updated. Using this cached copy so we don't have to
     * create it all the time..
     */
    protected List<PlayPlugin> allPlugins_readOnlyCopy = createReadonlyCopy(allPlugins);

    /**
     * List of all enabled plugins
     */
    protected List<PlayPlugin> enabledPlugins = new ArrayList<>();

    /**
     * Readonly copy of enabledPlugins - updated each time enabledPlugins is updated. Using this cached copy so we don't
     * have to create it all the time
     */
    protected List<PlayPlugin> enabledPlugins_readOnlyCopy = createReadonlyCopy(enabledPlugins);

    /**
     * List of all enabled plugins with filters
     */
    protected List<PlayPlugin> enabledPluginsWithFilters = new ArrayList<>();

    /**
     * Readonly copy of enabledPluginsWithFilters - updated each time enabledPluginsWithFilters is updated. Using this
     * cached copy so we don't have to create it all the time
     */
    protected List<PlayPlugin> enabledPluginsWithFilters_readOnlyCopy = createReadonlyCopy(enabledPluginsWithFilters);

    /**
     * Using readonly list to crash if someone tries to modify the copy.
     * 
     * @param list
     *            The list of plugins
     * @return Read only list of plugins
     */
    protected List<PlayPlugin> createReadonlyCopy(List<PlayPlugin> list) {
        return Collections.unmodifiableList(new ArrayList<>(list));
    }

    private static class LoadingPluginInfo implements Comparable<LoadingPluginInfo> {
        public final String name;
        public final int index;
        public final URL url;

        private LoadingPluginInfo(String name, int index, URL url) {
            this.name = name;
            this.index = index;
            this.url = url;
        }

        @Override
        public String toString() {
            return String.format("LoadingPluginInfo{name='%s', index=%s, url=%s}", name, index, url);
        }

        @Override
        public int compareTo(LoadingPluginInfo o) {
            int res = index < o.index ? -1 : (index == o.index ? 0 : 1);
            if (res != 0) {
                return res;
            }

            // Index is equal in both plugins.
            // sort on name to get consistent order
            return name.compareTo(o.name);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            LoadingPluginInfo that = (LoadingPluginInfo) o;
            return Objects.equals(index, that.index) && Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return hash(name, index);
        }
    }

    public void loadPlugins() {
        logger.trace("Loading plugins");
        List<URL> urls = loadPlayPluginDescriptors();

        // First we build one big SortedSet of all plugins to load (sorted based on index)
        // This must be done to make sure the enhancing is happening
        // when loading plugins using other classes that must be enhanced.
        SortedSet<LoadingPluginInfo> pluginsToLoad = new TreeSet<>();
        for (URL url : urls) {
            logger.trace("Found one plugins descriptor, {}", url);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream(), "utf-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    String[] lineParts = line.split(":");
                    LoadingPluginInfo info = new LoadingPluginInfo(lineParts[1].trim(), Integer.parseInt(lineParts[0]), url);
                    pluginsToLoad.add(info);
                }
            } catch (Exception e) {
                logger.error("Error interpreting {}", url, e);
            }
        }

        for (LoadingPluginInfo info : pluginsToLoad) {
            logger.trace("Loading plugin {}", info.name);
            try {
                PlayPlugin plugin = (PlayPlugin) Injector.getBeanOfType(Class.forName(info.name));
                plugin.index = info.index;
                if (addPlugin(plugin)) {
                    logger.trace("Plugin {} loaded", plugin);
                } else {
                    logger.warn("Did not load plugin {}. Already loaded", plugin);
                }
            } catch (Exception ex) {
                logger.error("Error loading plugin {}", info, ex);
            }
        }
        // Now we must call onLoad for all plugins - and we must detect if a
        // plugin
        // disables another plugin the old way, by removing it from
        // Play.plugins.
        for (PlayPlugin plugin : getEnabledPlugins()) {

            // is this plugin still enabled?
            if (isEnabled(plugin)) {
                initializePlugin(plugin);
            }
        }
    }

    List<URL> loadPlayPluginDescriptors() {
        String[] pluginsDescriptorFilenames = Play.configuration.getProperty("play.plugins.descriptor", "play.plugins").split(",");
        List<URL> pluginDescriptors = Arrays.stream(pluginsDescriptorFilenames)
          .map(f -> getResources(f))
          .flatMap(List::stream)
          .collect(toList());
        logger.info("Found plugin descriptors: {}", pluginDescriptors);
        return pluginDescriptors;
    }

    private List<URL> getResources(String f) {
        try {
            return list(Thread.currentThread().getContextClassLoader().getResources(f));
        }
        catch (IOException e) {
            throw new IllegalArgumentException("Failed to read plugins from " + f);
        }
    }

    /**
     * Calls plugin.onLoad but detects if plugin removes other plugins from Play.plugins-list to detect if plugins
     * disables a plugin the old hacked way..
     * 
     * @param plugin
     *            The given plugin
     */
    protected void initializePlugin(PlayPlugin plugin) {
        logger.trace("Initializing plugin {}", plugin);
        // We're ready to call onLoad for this plugin.
        // must create a unique Play.plugins-list for this onLoad-method-call so
        // we can detect if some plugins are removed/disabled
        List<PlayPlugin> plugins = new ArrayList<>(getEnabledPlugins());
        plugin.onLoad();
        // Check for missing/removed plugins
        for (PlayPlugin enabledPlugin : getEnabledPlugins()) {
            if (!plugins.contains(enabledPlugin)) {
                logger.info("Detected that plugin '{}' disabled the plugin '{}' the old way - should use Play.disablePlugin()", plugin, enabledPlugin);
                // This enabled plugin was disabled.
                // must disable it in pluginCollection
                disablePlugin(enabledPlugin);
            }
        }
    }

    /**
     * Adds one plugin and enables it
     * 
     * @param plugin
     *            The given plugin
     * @return true if plugin was new and was added
     */
    protected synchronized boolean addPlugin(PlayPlugin plugin) {
        if (!allPlugins.contains(plugin)) {
            allPlugins.add(plugin);
            Collections.sort(allPlugins);
            allPlugins_readOnlyCopy = createReadonlyCopy(allPlugins);
            enablePlugin(plugin);
            return true;
        }
        return false;
    }

    /**
     * Enable plugin.
     *
     * @param plugin
     *            The given plugin
     * @return true if plugin exists and was enabled now
     */
    public synchronized boolean enablePlugin(PlayPlugin plugin) {
        if (allPlugins.contains(plugin)) {
            // the plugin exists
            if (!enabledPlugins.contains(plugin)) {
                // plugin not currently enabled
                enabledPlugins.add(plugin);
                Collections.sort(enabledPlugins);
                enabledPlugins_readOnlyCopy = createReadonlyCopy(enabledPlugins);

                if (plugin.hasFilter()) {
                    enabledPluginsWithFilters.add(plugin);
                    Collections.sort(enabledPluginsWithFilters);
                    enabledPluginsWithFilters_readOnlyCopy = createReadonlyCopy(enabledPluginsWithFilters);
                }

                logger.trace("Plugin {} enabled", plugin);
                return true;
            }
        }

        return false;
    }

    /**
     * enable plugin of specified type
     * 
     * @param pluginClazz
     *            The plugin class
     * 
     * @return true if plugin was enabled
     */
    public boolean enablePlugin(Class<? extends PlayPlugin> pluginClazz) {
        return enablePlugin(getPluginInstance(pluginClazz));
    }

    /**
     * Returns the first instance of a loaded plugin of specified type
     * 
     * @param pluginClazz
     *            The plugin class
     * @return PlayPlugin
     */
    public synchronized <T extends PlayPlugin> T getPluginInstance(Class<T> pluginClazz) {
        for (PlayPlugin p : getAllPlugins()) {
            if (pluginClazz.isInstance(p)) {
                return (T) p;
            }
        }
        return null;
    }

    /**
     * disable plugin
     * 
     * @param plugin
     *            The given plugin
     * @return true if plugin was enabled and now is disabled
     */
    public synchronized boolean disablePlugin(PlayPlugin plugin) {
        // try to disable it?
        if (enabledPlugins.remove(plugin)) {
            // plugin was removed
            enabledPlugins_readOnlyCopy = createReadonlyCopy(enabledPlugins);

            if (enabledPluginsWithFilters.remove(plugin)) {
                enabledPluginsWithFilters_readOnlyCopy = createReadonlyCopy(enabledPluginsWithFilters);
            }

            logger.trace("Plugin {} disabled", plugin);
            return true;
        }
        return false;
    }

    /**
     * Disable plugin of specified type
     * 
     * @param pluginClazz
     *            The plugin class
     * 
     * @return true if plugin was enabled and now is disabled
     */
    public boolean disablePlugin(Class<? extends PlayPlugin> pluginClazz) {
        return disablePlugin(getPluginInstance(pluginClazz));
    }

    /**
     * Returns new readonly list of all enabled plugins
     * 
     * @return List of plugins
     */
    public List<PlayPlugin> getEnabledPlugins() {
        return enabledPlugins_readOnlyCopy;
    }

    /**
     * Returns new readonly list of all enabled plugins that define filters.
     * 
     * @return List of plugins
     */
    public List<PlayPlugin> getEnabledPluginsWithFilters() {
        return enabledPluginsWithFilters_readOnlyCopy;
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<PlayPlugin.Filter<T>> composeFilters() {
        // Copy list of plugins here in case the list changes in the midst of
        // doing composition...
        // (Is it really necessary to do this?)
        List<PlayPlugin> pluginsWithFilters = new ArrayList<>(this.getEnabledPluginsWithFilters());

        if (pluginsWithFilters.isEmpty()) {
            return Optional.empty();
        } else {
            Iterator<PlayPlugin> itr = pluginsWithFilters.iterator();
            PlayPlugin.Filter<T> ret = itr.next().getFilter();
            while (itr.hasNext()) {
                ret = ret.<T> decorate(itr.next().getFilter());
            }
            return Optional.of(ret);
        }
    }

    /**
     * Returns readonly view of all enabled plugins in reversed order
     * 
     * @return Collection of plugins
     */
    List<PlayPlugin> getReversedEnabledPlugins() {
        ArrayList<PlayPlugin> reversedPlugins = new ArrayList<>(enabledPlugins);
        Collections.reverse(reversedPlugins);
        return reversedPlugins;
    }

    /**
     * Returns new readonly list of all plugins
     * 
     * @return List of plugins
     */
    public List<PlayPlugin> getAllPlugins() {
        return allPlugins_readOnlyCopy;
    }

    /**
     * Indicate if a plugin is enabled
     * 
     * @param plugin
     *            The given plugin
     * @return true if plugin is enabled
     */
    public boolean isEnabled(PlayPlugin plugin) {
        return getEnabledPlugins().contains(plugin);
    }

    public void invocationFinally() {
        for (PlayPlugin plugin : getEnabledPlugins()) {
            plugin.invocationFinally();
        }
    }

    public void beforeInvocation() {
        for (PlayPlugin plugin : getEnabledPlugins()) {
            plugin.beforeInvocation();
        }
    }

    public void afterInvocation() {
        for (PlayPlugin plugin : getEnabledPlugins()) {
            plugin.afterInvocation();
        }
    }

    public void onInvocationSuccess() {
        for (PlayPlugin plugin : getEnabledPlugins()) {
            plugin.onInvocationSuccess();
        }
    }

    public void onInvocationException(Throwable e) {
        for (PlayPlugin plugin : getEnabledPlugins()) {
            try {
                plugin.onInvocationException(e);
            } catch (Throwable ex) {
                logger.error("Failed to handle invocation exception by plugin {}", plugin.getClass().getName(), ex);
            }
        }
    }

    public void detectChange() {
        for (PlayPlugin plugin : getEnabledPlugins()) {
            plugin.detectChange();
        }
    }

    public void onConfigurationRead() {
        for (PlayPlugin plugin : getEnabledPlugins()) {
            plugin.onConfigurationRead();
        }
    }

    public void onApplicationStart() {
        for (PlayPlugin plugin : getEnabledPlugins()) {
            plugin.onApplicationStart();
        }
    }

    public void afterApplicationStart() {
        for (PlayPlugin plugin : getEnabledPlugins()) {
            plugin.afterApplicationStart();
        }
    }

    public void onApplicationStop() {
        for (PlayPlugin plugin : getReversedEnabledPlugins()) {
            try {
                plugin.onApplicationStop();
            } catch (Throwable t) {
                logger.error("Error while stopping {}", plugin, t);
            }
        }
    }

    public void onEvent(String message, Object context) {
        for (PlayPlugin plugin : getEnabledPlugins()) {
            plugin.onEvent(message, context);
        }
    }

    public Object bind(RootParamNode rootParamNode, String name, Class<?> clazz, Type type, Annotation[] annotations) {
        for (PlayPlugin plugin : getEnabledPlugins()) {
            Object result = plugin.bind(rootParamNode, name, clazz, type, annotations);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    public Model.Factory modelFactory(Class<? extends Model> modelClass) {
        for (PlayPlugin plugin : getEnabledPlugins()) {
            Model.Factory factory = plugin.modelFactory(modelClass);
            if (factory != null) {
                return factory;
            }
        }
        return null;
    }

    public String getMessage(String locale, Object key, Object... args) {
        for (PlayPlugin plugin : getEnabledPlugins()) {
            String message = plugin.getMessage(locale, key, args);
            if (message != null) {
                return message;
            }
        }
        return null;
    }

    public void beforeActionInvocation(Method actionMethod) {
        for (PlayPlugin plugin : getEnabledPlugins()) {
            plugin.beforeActionInvocation(actionMethod);
        }
    }

    public void onActionInvocationResult(Result result) {
        for (PlayPlugin plugin : getEnabledPlugins()) {
            plugin.onActionInvocationResult(result);
        }
    }

    public void afterActionInvocation() {
        for (PlayPlugin plugin : getEnabledPlugins()) {
            plugin.afterActionInvocation();
        }
    }

    public void onActionInvocationFinally() {
        for (PlayPlugin plugin : getEnabledPlugins()) {
            plugin.onActionInvocationFinally();
        }
    }

    public void routeRequest(Http.Request request) {
        for (PlayPlugin plugin : getEnabledPlugins()) {
            plugin.routeRequest(request);
        }
    }

    public void onRequestRouting(Router.Route route) {
        for (PlayPlugin plugin : getEnabledPlugins()) {
            plugin.onRequestRouting(route);
        }
    }

    public void onRoutesLoaded() {
        for (PlayPlugin plugin : getEnabledPlugins()) {
            plugin.onRoutesLoaded();
        }
    }

    public boolean rawInvocation(Http.Request request, Http.Response response) throws Exception {
        for (PlayPlugin plugin : getEnabledPlugins()) {
            if (plugin.rawInvocation(request, response)) {
                return true;
            }
        }
        return false;
    }

    public Template loadTemplate(VirtualFile file) {
        for (PlayPlugin plugin : getEnabledPlugins()) {
            Template pluginProvided = plugin.loadTemplate(file);
            if (pluginProvided != null) {
                return pluginProvided;
            }
        }
        return null;
    }
}
