package mcinterface1201;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import minecrafttransportsimulator.blocks.components.ABlockBase;
import minecrafttransportsimulator.blocks.components.ABlockBaseTileEntity;
import minecrafttransportsimulator.blocks.instances.BlockCollision;
import minecrafttransportsimulator.blocks.tileentities.components.ITileEntityEnergyCharger;
import minecrafttransportsimulator.blocks.tileentities.components.ITileEntityFluidTankProvider;
import minecrafttransportsimulator.blocks.tileentities.components.ITileEntityInventoryProvider;
import minecrafttransportsimulator.items.components.AItemPack;
import minecrafttransportsimulator.items.components.AItemSubTyped;
import minecrafttransportsimulator.items.components.IItemBlock;
import minecrafttransportsimulator.items.components.IItemEntityProvider;
import minecrafttransportsimulator.items.components.IItemFood;
import minecrafttransportsimulator.items.instances.ItemItem;
import minecrafttransportsimulator.jsondefs.JSONPack;
import minecrafttransportsimulator.mcinterface.IInterfaceCore;
import minecrafttransportsimulator.mcinterface.InterfaceManager;
import minecrafttransportsimulator.packloading.PackParser;
import minecrafttransportsimulator.systems.ConfigSystem;
import minecrafttransportsimulator.systems.LanguageSystem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTab.DisplayItemsGenerator;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLConstructModEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.DeferredRegister;

/**
 * Loader interface for the mod.  This class is not actually an interface, unlike everything else.
 * Instead, it keeps references to all interfaces, which are passed-in during construction.
 * It also handles initialization calls when the game is first booted.  There will only
 * be ONE loader per running instance of Minecraft.
 *
 * @author don_bruce
 */
@Mod(InterfaceLoader.MODID)
public class InterfaceLoader {
    public static final String MODID = "mts";
    public static final String MODNAME = "Immersive Vehicles (MTS)";
    public static final String MODVER = "22.17.0";

    private final FMLJavaModLoadingContext context;
    public static final Logger LOGGER = LogManager.getLogger(InterfaceLoader.MODID);
    private final String gameDirectory;
    public static Set<String> packIDs = new HashSet<>();

    private static List<BuilderBlock> normalBlocks = new ArrayList<>();
    private static List<BuilderBlock> fluidBlocks = new ArrayList<>();
    private static List<BuilderBlock> inventoryBlocks = new ArrayList<>();
    private static List<BuilderBlock> chargerBlocks = new ArrayList<>();
    protected static final DeferredRegister<CreativeModeTab> CREATIVE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, InterfaceLoader.MODID);

    public InterfaceLoader() {
        this.context = FMLJavaModLoadingContext.get();
        this.gameDirectory = FMLPaths.GAMEDIR.get().toFile().getAbsolutePath();
        context.getModEventBus().addListener(this::init);
        context.getModEventBus().addListener(this::onPostConstruction);
    }

    /**Need to defer init until post-mod construction, as in this version
     * {@link IInterfaceCore#getModName(String)} requires a constructor pack-mod
     * instance to query the classloader for a resource, and we need that for pack
     * init in the boot calls.
     * 
     */
    public void init(FMLConstructModEvent event) {
        //Add registries.
        BuilderItem.ITEMS.register(context.getModEventBus());
        BuilderBlock.BLOCKS.register(context.getModEventBus());
        BuilderTileEntity.TILE_ENTITIES.register(context.getModEventBus());
        ABuilderEntityBase.ENTITIES.register(context.getModEventBus());
        CREATIVE_TABS.register(context.getModEventBus());

        //Need to do pack parsing first, since that generates items which have to be registered prior to any other events.
        boolean isClient = FMLEnvironment.dist.isClient();

        //Init interfaces and send to the main game system.
        if (isClient) {
            new InterfaceManager(MODID, gameDirectory, new InterfaceCore(), new InterfacePacket(), new InterfaceClient(), new InterfaceInput(), new InterfaceSound(), new InterfaceRender());
            context.getModEventBus().addListener(InterfaceInput::onIVRegisterKeyMappingsEvent);
            context.getModEventBus().addListener(InterfaceRender::onIVRegisterShadersEvent);
            context.getModEventBus().addListener(InterfaceRender::onIVRegisterRenderersEvent);
        } else {
            new InterfaceManager(MODID, gameDirectory, new InterfaceCore(), new InterfacePacket(), null, null, null, null);
        }

        InterfaceManager.coreInterface.logError("Welcome to MTS VERSION: " + MODVER);

        //Parse packs
        ConfigSystem.loadFromDisk(new File(gameDirectory, "config"), isClient);
        List<File> packDirectories = new ArrayList<>();
        File modDirectory = new File(gameDirectory, "mods");
        if (modDirectory.exists()) {
            packDirectories.add(modDirectory);

            //Parse the packs.
            PackParser.addDefaultItems();
            PackParser.parsePacks(packDirectories);
        } else {
            InterfaceManager.coreInterface.logError("Could not find mods directory!  Game directory is confirmed to: " + gameDirectory);
        }

        //Set pack IDs.
        packIDs.addAll(PackParser.getAllPackIDs());

        //Create all pack items.  We need to do this before anything else.
        //Item registration comes first, and we use the items registered to determine
        //which blocks we need to register.
        Set<ABlockBase> blocksRegistred = new HashSet<>();
        Map<String, List<AItemPack<?>>> creativeTabsRequired = new HashMap<>();
        for (String packID : PackParser.getAllPackIDs()) {
            for (AItemPack<?> item : PackParser.getAllItemsForPack(packID, true)) {
                if (item.autoGenerate()) {
                    //Crate the item registry creator.
                    BuilderItem.ITEMS.register(item.getRegistrationName(), () -> {
                        Item.Properties itemProperties = new Item.Properties();
                        itemProperties.stacksTo(item.getStackSize());
                        if (item instanceof ItemItem && ((ItemItem) item).definition.food != null) {
                            IItemFood food = (IItemFood) item;
                            itemProperties.food(new FoodProperties.Builder().nutrition(food.getHungerAmount()).saturationMod(food.getSaturationAmount()).build());
                        }
                        return new BuilderItem(itemProperties, item);
                    });

                    //Check if the creative tab is set/created.
                    //The only exception is for "invisible" parts of the core mod, these are internal.
                    boolean hideOnCreativeTab = item.definition.general.hideOnCreativeTab || (item instanceof AItemSubTyped && ((AItemSubTyped<?>) item).subDefinition.hideOnCreativeTab);
                    if (!hideOnCreativeTab && (!item.definition.packID.equals(InterfaceLoader.MODID) || !item.definition.systemName.contains("invisible"))) {
                        creativeTabsRequired.computeIfAbsent(item.getCreativeTabID(), k -> new ArrayList<>()).add(item);
                    }
                }

                //If this item is an IItemBlock, generate a block in the registry for it.
                //iterate over all items and get the blocks they spawn.
                //Not only does this prevent us from having to manually set the blocks
                //we also pre-generate the block classes here and note which tile entities they go to.
                if (item instanceof IItemBlock) {
                    ABlockBase itemBlockBlock = ((IItemBlock) item).getBlock();
                    if (!blocksRegistred.contains(itemBlockBlock)) {
                        //New block class detected.  Register it and its instance.
                        String name = itemBlockBlock.getClass().getSimpleName().substring("Block".length()).toLowerCase(Locale.ROOT);
                        blocksRegistred.add(itemBlockBlock);
                        BuilderBlock.BLOCKS.register(name, () -> {
                            BuilderBlock wrapper;

                            if (itemBlockBlock instanceof ABlockBaseTileEntity) {
                                wrapper = new BuilderBlockTileEntity(itemBlockBlock);
                                if (ITileEntityFluidTankProvider.class.isAssignableFrom(((ABlockBaseTileEntity) itemBlockBlock).getTileEntityClass())) {
                                    fluidBlocks.add(wrapper);
                                } else if (ITileEntityInventoryProvider.class.isAssignableFrom(((ABlockBaseTileEntity) itemBlockBlock).getTileEntityClass())) {
                                    inventoryBlocks.add(wrapper);
                                } else if (ITileEntityEnergyCharger.class.isAssignableFrom(((ABlockBaseTileEntity) itemBlockBlock).getTileEntityClass())) {
                                    chargerBlocks.add(wrapper);
                                } else {
                                    normalBlocks.add(wrapper);
                                }
                            } else {
                                wrapper = new BuilderBlock(itemBlockBlock);
                            }
                            BuilderBlock.blockMap.put(itemBlockBlock, wrapper);

                            return wrapper;
                        });
                    }
                }
            }
        }

        //Create creative tabs, as required.
        creativeTabsRequired.forEach((tabID, tabItems) -> {
            CREATIVE_TABS.register(tabID, () -> {
                JSONPack packConfiguration = PackParser.getPackConfiguration(tabID);
                AItemPack<?> tabIconItem = packConfiguration.packItem != null ? PackParser.getItem(packConfiguration.packID, packConfiguration.packItem) : null;
                ItemStack tabIconStack = tabIconItem != null ? new ItemStack(BuilderItem.itemMap.get(tabIconItem)) : null;
                DisplayItemsGenerator validItemsGenerator = (pParameters, pOutput) -> tabItems.forEach(tabItem -> pOutput.accept(BuilderItem.itemMap.get(tabItem)));
                Supplier<ItemStack> iconSupplier = tabIconStack != null ? () -> tabIconStack : () -> new ItemStack(BuilderItem.itemMap.get(tabItems.get((int) (System.currentTimeMillis() / 1000 % tabItems.size()))));
                return CreativeModeTab.builder().title(Component.literal(packConfiguration.packName)).icon(iconSupplier).displayItems(validItemsGenerator).build();
            });
        });

        //Register the collision blocks.
        for (int i = 0; i < BlockCollision.blockInstances.size(); ++i) {
            BlockCollision collisionBlock = BlockCollision.blockInstances.get(i);
            String name = collisionBlock.getClass().getSimpleName().substring("Block".length()).toLowerCase(Locale.ROOT) + i;
            BuilderBlock.BLOCKS.register(name, () -> {
                BuilderBlock wrapper = new BuilderBlock(collisionBlock);
                BuilderBlock.blockMap.put(collisionBlock, wrapper);
                return wrapper;
            });
        }

        //If we are on the client, create models.
        if (isClient) {
            InterfaceEventsModelLoader.init();
        }

        //Init the language system for the created items and blocks.
        LanguageSystem.init(isClient);

        //Init tile entities.  These will run after blocks, so the tile entity lists will be populated by this time.
        BuilderTileEntity.TE_TYPE = BuilderTileEntity.TILE_ENTITIES.register("builder_base", () -> BlockEntityType.Builder.of(BuilderTileEntity::new, normalBlocks.toArray(new BuilderBlock[0])).build(null));
        BuilderTileEntityFluidTank.TE_TYPE2 = BuilderTileEntity.TILE_ENTITIES.register("builder_fluidtank", () -> BlockEntityType.Builder.of(BuilderTileEntityFluidTank::new, fluidBlocks.toArray(new BuilderBlock[0])).build(null));
        BuilderTileEntityInventoryContainer.TE_TYPE2 = BuilderTileEntity.TILE_ENTITIES.register("builder_inventory", () -> BlockEntityType.Builder.of(BuilderTileEntityInventoryContainer::new, inventoryBlocks.toArray(new BuilderBlock[0])).build(null));
        BuilderTileEntityEnergyCharger.TE_TYPE2 = BuilderTileEntity.TILE_ENTITIES.register("builder_charger", () -> BlockEntityType.Builder.of(BuilderTileEntityEnergyCharger::new, chargerBlocks.toArray(new BuilderBlock[0])).build(null));

        //Init entities.
        BuilderEntityExisting.E_TYPE2 = ABuilderEntityBase.ENTITIES.register("builder_existing", () -> EntityType.Builder.<BuilderEntityExisting>of(BuilderEntityExisting::new, MobCategory.MISC).sized(0.05F, 0.05F).clientTrackingRange(32 * 16).updateInterval(5).build("builder_existing"));
        BuilderEntityLinkedSeat.E_TYPE3 = ABuilderEntityBase.ENTITIES.register("builder_seat", () -> EntityType.Builder.<BuilderEntityLinkedSeat>of(BuilderEntityLinkedSeat::new, MobCategory.MISC).sized(0.05F, 0.05F).clientTrackingRange(32 * 16).updateInterval(5).build("builder_seat"));
        BuilderEntityRenderForwarder.E_TYPE4 = ABuilderEntityBase.ENTITIES.register("builder_rendering", () -> EntityType.Builder.<BuilderEntityRenderForwarder>of(BuilderEntityRenderForwarder::new, MobCategory.MISC).sized(0.05F, 0.05F).clientTrackingRange(32 * 16).updateInterval(5).build("builder_rendering"));

        //Iterate over all pack items and find those that spawn entities.
        //Register these with the IV internal system.
        for (AItemPack<?> packItem : PackParser.getAllPackItems()) {
            if (packItem instanceof IItemEntityProvider) {
                ((IItemEntityProvider) packItem).registerEntities(BuilderEntityExisting.entityMap);
            }
        }

        //Init networking interface.  This will register packets as well.
        InterfacePacket.init();

        if (isClient) {
            //Init keybinds if we're on the client.
            InterfaceManager.inputInterface.initConfigKey();

            //Save modified config.
            ConfigSystem.saveToDisk();
        }
    }

    public void onPostConstruction(FMLLoadCompleteEvent event) {
        //Populate language system, since we now know we have a language class.
        if (FMLEnvironment.dist.isClient()) {
            LanguageSystem.populateNames();

            //Put all liquids into the config file for use by modpack makers.
            ConfigSystem.settings.fuel.lastLoadedFluids = InterfaceManager.clientInterface.getAllFluidNames();

            //Save modified config.
            ConfigSystem.saveToDisk();
        }
    }
}
