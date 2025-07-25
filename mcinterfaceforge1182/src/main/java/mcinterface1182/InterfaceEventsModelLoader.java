package mcinterface1182;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import minecrafttransportsimulator.items.components.AItemPack;
import minecrafttransportsimulator.mcinterface.InterfaceManager;
import minecrafttransportsimulator.packloading.PackParser;
import minecrafttransportsimulator.packloading.PackResourceLoader;
import minecrafttransportsimulator.packloading.PackResourceLoader.ResourceType;
import minecrafttransportsimulator.systems.ConfigSystem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;

/**
 * Interface for handling events pertaining to loading models into MC.  These events are mainly for item models,
 * though events for Entity and Tile Entity model rendering classes are also included here as they are registered
 * like item models.
 *
 * @author don_bruce
 */
public class InterfaceEventsModelLoader {
    public static PackResourcePack packPack = new PackResourcePack();

    /**
     * Called to init the custom model loader.  Should be done before any other things.
     * This allows injecting our custom resource manager into MC's systems to have it use it.
     * We do this by registering it as a reload listener, as on a resource reload (and boot) MC will purge the list
     * of packs and will re-query from disk.  But we aren't on disk, and so we will need to be
     * ready when that call comes and will re-add ourselves.
     */
    public static void init() {
        packPack.domains.addAll(PackParser.getAllPackIDs());
    }

    /**
     * Custom ResourcePack class for auto-generating item JSONs.
     */
    public static class PackResourcePack implements PackResources {
        private final Set<String> domains;
        private final Set<String> fakeDomains;

        private PackResourcePack() {
            super();
            domains = new HashSet<>();
            fakeDomains = new HashSet<>();
            fakeDomains.add(InterfaceManager.coreModID);
        }

        @Override
        public InputStream getResource(PackType type, ResourceLocation location) throws IOException {
            //Create stream return variable and get raw data.
            InputStream stream;
            String domain = !location.getNamespace().equals(InterfaceManager.coreModID) ? location.getNamespace() : getPackID(location.getPath());
            String rawPackInfo = location.getPath();

            //Try to get the specified file as-is first.
            stream = InterfaceManager.coreInterface.getPackResource("/assets/" + domain + "/" + rawPackInfo);
            if (stream == null) {
                //If we are for an item JSON, try to find that JSON, or generate one automatically.
                //If we are for an item PNG, just load the PNG as-is.  If we don't find it, then just let MC purple checker it.
                //Note that the internal mts_packs loader does not do PNG loading, as it re-directs the PNG files to the pack's loaders.
                if (rawPackInfo.endsWith(".json")) {
                    //Strip the suffix from the packInfo, and then test to see if it's an internal
                    //JSON reference from an item JSON, or if it's the primary JSON for the item being loaded..
                    String strippedSuffix = rawPackInfo.substring(0, rawPackInfo.lastIndexOf("."));
                    String resourcePath = "";
                    String itemTexturePath = "";

                    //Strip off the auto-generated prefix.
                    String combinedPackInfo;
                    combinedPackInfo = strippedSuffix.substring("models/item/".length());

                    //Get the pack information, and try to load the resource.
                    try {
                        String packID = combinedPackInfo.substring(0, combinedPackInfo.indexOf("."));
                        String systemName = combinedPackInfo.substring(combinedPackInfo.indexOf(".") + 1);
                        AItemPack<?> packItem = PackParser.getItem(packID, systemName);
                        resourcePath = PackResourceLoader.getPackResource(packItem.definition, ResourceType.ITEM_JSON, systemName);

                        //Try to load the item JSON, or create it if it doesn't exist.
                        stream = InterfaceManager.coreInterface.getPackResource(resourcePath);
                        if (stream == null) {
                            //Get the actual texture path.
                            itemTexturePath = PackResourceLoader.getPackResource(packItem.definition, ResourceType.ITEM_PNG, systemName);

                            //Remove the "/assets/packID/" portion as it's implied with JSON.
                            itemTexturePath = itemTexturePath.substring(("/assets/" + packID + "/").length());

                            //Remove the .png suffix as it's also implied.
                            itemTexturePath = itemTexturePath.substring(0, itemTexturePath.length() - ".png".length());

                            //Need to add packID domain to this to comply with JSON domains.
                            //If we don't, the PNG won't get sent to the right loader.
                            itemTexturePath = packID + ":" + itemTexturePath;

                            //Generate fake JSON and return as stream to MC loader.
                            String fakeJSON = "{\"parent\":\"mts:item/basic\",\"textures\":{\"layer0\": \"" + itemTexturePath + "\"}}";
                            stream = new ByteArrayInputStream(fakeJSON.getBytes(StandardCharsets.UTF_8));
                        }
                    } catch (Exception e) {
                        if (ConfigSystem.settings.general.devMode.value) {
                            InterfaceManager.coreInterface.logError("Could not parse out item JSON from: " + rawPackInfo + "  Looked for JSON at:" + resourcePath + (itemTexturePath.isEmpty() ? (", with fallback at:" + itemTexturePath) : ", but could not find it."));
                        }
                        throw new FileNotFoundException(rawPackInfo);
                    }
                } else {
                    try {
                        //First check if this is for an item or a model.
                        boolean isItemPNG = rawPackInfo.contains("/items/") || rawPackInfo.contains("_item");

                        //Strip off the auto-generated prefix and suffix data.
                        String combinedPackInfo = rawPackInfo;
                        combinedPackInfo = combinedPackInfo.substring("textures/".length(), combinedPackInfo.length() - ".png".length());

                        //Get the pack information.
                        //If we are ending in _item, it means we are getting a JSON for a modular-pack's item PNG.
                        //Need to remove this suffix to get the correct systemName to look-up in the systems.
                        String packID = domain;
                        String systemName = combinedPackInfo.substring(combinedPackInfo.lastIndexOf('/') + 1);
                        if (systemName.endsWith("_item")) {
                            systemName = systemName.substring(0, systemName.length() - "_item".length());
                        }
                        AItemPack<?> packItem = PackParser.getItem(packID, systemName);

                        if (packItem != null) {
                            //Get the actual resource path for this resource and return its stream.
                            String streamLocation = PackResourceLoader.getPackResource(packItem.definition, isItemPNG ? ResourceType.ITEM_PNG : ResourceType.PNG, systemName);
                            stream = InterfaceManager.coreInterface.getPackResource(streamLocation);

                            if (stream == null) {
                                if (isItemPNG) {
                                    //We might not have this file, but we also might have a JSON-defined item here.
                                    //Try the JSON standards before throwing an error.
                                    String streamJSONLocation = "/assets/" + packID + "/" + rawPackInfo;
                                    stream = InterfaceManager.coreInterface.getPackResource(streamJSONLocation);
                                    if (stream == null) {
                                        if (ConfigSystem.settings.general.devMode.value) {
                                            if (streamLocation != null) {
                                                InterfaceManager.coreInterface.logError("Could not find item PNG at specified location: " + streamLocation + "  Or potential JSON location: " + streamJSONLocation);
                                            } else {
                                                InterfaceManager.coreInterface.logError("Could not find JSON PNG: " + streamJSONLocation);
                                            }
                                        }
                                        throw new FileNotFoundException(rawPackInfo);
                                    }
                                } else {
                                    if (ConfigSystem.settings.general.devMode.value) {
                                        InterfaceManager.coreInterface.logError("Could not find OBJ PNG: " + streamLocation);
                                    }
                                    throw new FileNotFoundException(rawPackInfo);
                                }
                            }
                        } else {
                            //No pack item for this texture.  Must be an internal texture for other things.
                            //In this case, we just get the stream exact location.
                            String streamLocation = "/assets/" + domain + "/" + rawPackInfo;
                            stream = InterfaceManager.coreInterface.getPackResource(streamLocation);
                            if (stream == null) {
                                if (ConfigSystem.settings.general.devMode.value) {
                                    InterfaceManager.coreInterface.logError("Couldn't find...whatever this is: " + streamLocation);
                                }
                                throw new FileNotFoundException(rawPackInfo);
                            }
                        }
                    } catch (Exception e) {
                        if (e instanceof FileNotFoundException) {
                            throw e;
                        } else {
                            if (ConfigSystem.settings.general.devMode.value) {
                                InterfaceManager.coreInterface.logError("Could not parse which item PNG to get from: " + rawPackInfo);
                            }
                            throw new FileNotFoundException(rawPackInfo);
                        }
                    }
                }
            }

            //Return whichever stream we found.
            return stream;
        }

        @Override
        public boolean hasResource(PackType type, ResourceLocation location) {
            return (domains.contains(location.getNamespace()) || domains.contains(getPackID(location.getPath()))) && !location.getPath().contains("blockstates/") && !location.getPath().contains("armatures/") && !location.getPath().contains(".mcmeta") && !location.getPath().contains("advancements/") && !location.getPath().contains("recipes/") && !location.getPath().contains("lang/") && ((location.getPath().endsWith(".json") && !location.getPath().equals("sounds.json")) || location.getPath().endsWith(".png"));
        }

        @Override
        public Set<String> getNamespaces(PackType pType) {
            return fakeDomains;
        }

        @Override
        public <T> T getMetadataSection(MetadataSectionSerializer<T> pDeserializer) {
            return null;
        }

        @Override
        public String getName() {
            return InterfaceManager.coreModID + "_packs";
        }

        @Override
        public void close() {
        }

        @Override
        public InputStream getRootResource(String pFileName) throws IOException {
            if (!pFileName.contains("/") && !pFileName.contains("\\")) {
                return this.getResource(PackType.CLIENT_RESOURCES, new ResourceLocation(pFileName));
            } else {
                throw new IllegalArgumentException("Root resources can only be filenames, not paths (no / allowed!)");
            }
        }

        @Override
        public Collection<ResourceLocation> getResources(PackType pType, String pNamespace, String pPath, int pMaxDepth, Predicate<String> pFilter) {
            //We shouldn't never need this, our resources are on-demand.
            return new ArrayList<ResourceLocation>();
        }

        private static String getPackID(String path) {
            int distanceToFirstDot = path.indexOf(".");
            int distanceToSlashBefore = path.lastIndexOf("/", distanceToFirstDot);
            if (distanceToSlashBefore != -1) {
                String packID = path.substring(distanceToSlashBefore + 1, distanceToFirstDot);
                if (PackParser.getAllPackIDs().contains(packID)) {
                    return packID;
                }
            }
            //Not an actual pack resource, must be from core.
            return InterfaceManager.coreModID;
        }
    }
}
