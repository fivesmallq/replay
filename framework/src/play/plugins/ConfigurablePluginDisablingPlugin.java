package play.plugins;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.Play;
import play.PlayPlugin;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Plugin that reads list of plugins to disable from application.conf
 *
 *
 * To disable plugins, specify it like this in application.conf:
 *
 * plugins.disable=full-plugin-class-name
 * plugins.disable.0=full-plugin-class-name
 * plugins.disable.1=full-plugin-class-name
 * plugins.disable.whatever=full-plugin-class-name
 *
 */
public class ConfigurablePluginDisablingPlugin extends PlayPlugin {
    private static final Logger logger = LoggerFactory.getLogger(ConfigurablePluginDisablingPlugin.class);

    /**
     * List holding all disabled plugins.
     * when reloading config, we have to enable hem again, in case,
     * they are no longer listed in the "disable plugins"-section
     */
    protected static final Set<String> previousDisabledPlugins = new HashSet<>();

    @Override
    public void onConfigurationRead() {
        logger.trace("Looking for plugins to disable");


        Set<String> disabledPlugins = new HashSet<>();

        for( Map.Entry<Object, Object> e : Play.configuration.entrySet()){
            String key = (String)e.getKey();
            if( key.equals("plugins.disable") || key.startsWith("plugins.disable.")){
                String pluginClassName = (String)e.getValue();
                //try to find this class..
                Class<? extends PlayPlugin> clazz = resolveClass(pluginClassName);
                if( clazz != null ){

                    PlayPlugin pluginInstance = Play.pluginCollection.getPluginInstance( clazz );

                    if( pluginInstance != null ){

                        //try to disable it
                        //must remember that we have tries to disabled this plugin
                        disabledPlugins.add( pluginClassName );

                        if( Play.pluginCollection.disablePlugin( clazz)){
                            logger.info("Plugin disabled: {}", clazz);

                        }else{
                            logger.warn("Could not disable Plugin: {}. Already disabled?", clazz);
                        }
                    }else{
                        logger.error("Cannot disable plugin {}. No loaded plugin of that type", clazz);
                    }
                }
            }
        }

        //must look for plugins disabled the last time but not this time.. This can happen
        //when reloading config with changes in disable-list...

        for( String pluginClassName : previousDisabledPlugins ){
            if( !disabledPlugins.contains( pluginClassName)){
                logger.info("Enabling plugin {} since it is now longer listed in plugins.disable section in config", pluginClassName);
                Class<? extends PlayPlugin> clazz = resolveClass(pluginClassName);
                if( clazz != null ){
                    //try to disable it
                    if( Play.pluginCollection.enablePlugin( clazz)){
                        logger.info("Plugin reenabled: {}", clazz);
                    }else{
                        logger.warn("Could not reenable Plugin: {}", clazz);
                    }
                }
            }
        }

        //remember the plugins we disabled this time until the next time..
        previousDisabledPlugins.clear();
        previousDisabledPlugins.addAll(disabledPlugins);
        
    }

    @SuppressWarnings("unchecked")
    private Class<PlayPlugin> resolveClass(String pluginClassName) {
        try{
            return (Class<PlayPlugin>)getClass().getClassLoader().loadClass(pluginClassName);
        }catch(Exception e){
            logger.error("Could not disable plugin {}", pluginClassName, e);
        }
        return null;
    }
}
