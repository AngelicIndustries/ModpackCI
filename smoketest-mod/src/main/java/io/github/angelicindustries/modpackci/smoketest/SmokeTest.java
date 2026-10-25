package io.github.angelicindustries.modpackci.smoketest;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Only installed by the ModpackCI client smoke test. Once the main menu is up, opens a new
 * singleplayer world, lets it run, then quits the game. Vanilla classes are reached by reflection
 * with their SRG names, so this compiles against the Forge jar alone.
 */
@Mod(modid = "modpackci_smoketest", name = "ModpackCI smoke test", version = "1")
public class SmokeTest {
    static final int TICKS = 600;

    private boolean launched;
    private int ticksInWorld;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) throws ReflectiveOperationException {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Object mc = Class.forName("net.minecraft.client.Minecraft").getMethod("func_71410_x").invoke(null);
        if (!launched) {
            Object screen = mc.getClass().getField("field_71462_r").get(mc); // currentScreen
            if (screen != null && screen.getClass().getName().equals("net.minecraft.client.gui.GuiMainMenu")) {
                launched = true;
                createWorld(mc);
            }
            return;
        }
        if (mc.getClass().getField("field_71439_g").get(mc) == null) { // thePlayer
            return;
        }
        if (++ticksInWorld == TICKS) {
            System.out.println("ModpackCI smoke test: world ran for " + TICKS + " ticks");
            mc.getClass().getMethod("func_71400_g").invoke(mc); // shutdown
        }
    }

    private static void createWorld(Object mc) throws ReflectiveOperationException {
        Class<?> settingsClass = Class.forName("net.minecraft.world.WorldSettings");
        Class<?> gameType = Class.forName("net.minecraft.world.WorldSettings$GameType");
        Class<?> worldType = Class.forName("net.minecraft.world.WorldType");
        Object settings = settingsClass
                .getConstructor(long.class, gameType, boolean.class, boolean.class, worldType)
                .newInstance(20261025L, creative(gameType), true, false, worldType.getField("field_77137_b").get(null));
        System.out.println("ModpackCI smoke test: creating world");
        mc.getClass().getMethod("func_71371_a", String.class, String.class, settingsClass)
                .invoke(mc, "modpackci-smoketest", "ModpackCI smoke test", settings);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object creative(Class<?> gameType) {
        return Enum.valueOf((Class<Enum>) gameType, "CREATIVE");
    }
}
