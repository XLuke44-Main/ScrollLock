package xluke44.scrolllock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

import org.lwjgl.input.Keyboard;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent.KeyInputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

@Mod(
        modid = ScrollLock.MODID,
        name = ScrollLock.NAME,
        version = ScrollLock.VERSION,
        clientSideOnly = true,
        acceptableRemoteVersions = "*",
        acceptedMinecraftVersions = "1.12.2"
)

public class ScrollLock {
    public static final String MODID = "scrolllock";
    public static final String NAME = "ScrollLock";
    public static final String VERSION = "1.0.0";

    private static final String GENERAL_CATEGORY = "General";
    private static final String GLOBAL_CATEGORY = "Global";
    private static final String STATE_CATEGORY = "State";
    private static final String STATE_PROPERTY = "Enabled";

    private static final boolean DEFAULT_APPLY_GLOBALLY = false;
    private static final boolean DEFAULT_ENABLED_BY_DEFAULT = false;

    private static Configuration config;
    private static File globalStateFile;
    private static File worldsDirectory;
    private static File serverDirectDirectory;
    private static File serverListDirectory;

    private static Configuration currentStateConfig;
    private static String currentStatePath;
    private static String currentContextKey;
    private static String currentStateComment;
    private static boolean applyGlobally;
    private static boolean enabledByDefault;
    private static boolean enabled;

    private static final KeyBinding TOGGLE_KEY = new KeyBinding(
            "key.scrolllock.toggle",
            Keyboard.KEY_I,
            "key.categories.scrolllock"
    );

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        File configurationDirectory = event.getModConfigurationDirectory();
        File scrollLockDirectory = new File(configurationDirectory, MODID);
        globalStateFile = new File(scrollLockDirectory, "global.cfg");
        worldsDirectory = new File(scrollLockDirectory, "worlds");
        serverDirectDirectory = new File(scrollLockDirectory, "servers/direct");
        serverListDirectory = new File(scrollLockDirectory, "servers/entries");

        config = new Configuration(event.getSuggestedConfigurationFile(), true);
        loadConfiguration();

        clearCurrentState();
        enabled = enabledByDefault;
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        ClientRegistry.registerKeyBinding(TOGGLE_KEY);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private static void createDirectory(File directory) {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Unable to create directory: " + directory);
        }
    }

    private static void loadConfiguration() {
        config.load();

        applyGlobally = config.getBoolean(
                "ApplyGlobally",
                GENERAL_CATEGORY,
                DEFAULT_APPLY_GLOBALLY,
                "Use the same ScrollLock setting everywhere."
        );

        enabledByDefault = config.getBoolean(
                "EnabledByDefault",
                GENERAL_CATEGORY,
                DEFAULT_ENABLED_BY_DEFAULT,
                "Turn ScrollLock on by default."
        );

        config.setCategoryComment(GENERAL_CATEGORY, "ScrollLock Configuration");
        if (config.hasCategory(GLOBAL_CATEGORY)) {
            Property legacyGlobalState = config.getCategory(GLOBAL_CATEGORY).get(STATE_PROPERTY);
            if (legacyGlobalState != null) {
                createDirectory(globalStateFile.getParentFile());
                Configuration globalStateConfig = new Configuration(globalStateFile, true);
                globalStateConfig.load();
                if (!globalStateConfig.hasCategory(STATE_CATEGORY)
                        || !globalStateConfig.getCategory(STATE_CATEGORY).containsKey(STATE_PROPERTY)) {
                    Property stateProperty = globalStateConfig.get(
                            STATE_CATEGORY,
                            STATE_PROPERTY,
                            legacyGlobalState.getBoolean()
                    );
                    stateProperty.setComment(null);
                    stateProperty.set(legacyGlobalState.getBoolean());
                    globalStateConfig.setCategoryComment(
                            STATE_CATEGORY,
                            "Saved Global ScrollLock state."
                    );
                    saveIfChanged(globalStateConfig);
                }
            }
            config.removeCategory(config.getCategory(GLOBAL_CATEGORY));
        }

        saveIfChanged(config);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        String contextKey = getContextKey(minecraft);
        if (contextKey.equals(currentContextKey)) {
            return;
        }

        currentContextKey = contextKey;
        refreshStateForCurrentContext();
    }

    @SubscribeEvent
    public void onKeyInput(KeyInputEvent event) {
        if (!TOGGLE_KEY.isPressed()) {
            return;
        }

        refreshStateForCurrentContext();
        enabled = !enabled;
        saveCurrentState();
        showToggleMessage();
    }

    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        if (event.getDwheel() == 0) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.currentScreen == null && enabled) {
            event.setCanceled(true);
        }
    }

    private static void refreshStateForCurrentContext() {
        String statePath = getCurrentStatePath();
        if (statePath == null) {
            clearCurrentState();
            enabled = enabledByDefault;
            return;
        }

        if (statePath.equals(currentStatePath)) {
            return;
        }

        currentStatePath = statePath;

        currentStateComment = getCurrentStateComment();
        currentStateConfig = new Configuration(new File(statePath), true);
        currentStateConfig.load();
        Property stateProperty = currentStateConfig.get(
                STATE_CATEGORY,
                STATE_PROPERTY,
                enabledByDefault
        );
        stateProperty.setComment(null);
        enabled = stateProperty.getBoolean(enabledByDefault);
        currentStateConfig.setCategoryComment(STATE_CATEGORY, currentStateComment);
        saveIfChanged(currentStateConfig);
    }

    private static void clearCurrentState() {
        currentStateConfig = null;
        currentStatePath = null;
        currentStateComment = null;
    }

    private static void saveCurrentState() {
        if (currentStatePath == null) {
            return;
        }

        if (currentStateConfig == null) {
            return;
        }

        Property stateProperty = currentStateConfig.get(
                STATE_CATEGORY,
                STATE_PROPERTY,
                enabled
        );
        stateProperty.setComment(null);
        stateProperty.set(enabled);
        saveIfChanged(currentStateConfig);
    }

    private static void showToggleMessage() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.player == null) {
            return;
        }

        minecraft.player.sendStatusMessage(
                new TextComponentTranslation(enabled
                        ? "message.scrolllock.enabled"
                        : "message.scrolllock.disabled"),
                true
        );
    }

    private static String getCurrentStateComment() {
        if (applyGlobally) {
            return "Saved Global ScrollLock state.";
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.isSingleplayer()) {
            return "Saved ScrollLock state for this world.";
        }

        return "Saved ScrollLock state for this server.";
    }

    private static String getCurrentStatePath() {
        if (applyGlobally) {
            createDirectory(globalStateFile.getParentFile());
            return globalStateFile.getAbsolutePath();
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.world == null) {
            return null;
        }

        if (minecraft.isSingleplayer()) {
            if (minecraft.getIntegratedServer() != null) {
                File stateFile = new File(
                        worldsDirectory,
                        sanitizeFileName(minecraft.getIntegratedServer().getFolderName()) + ".cfg"
                );
                createDirectory(stateFile.getParentFile());
                return stateFile.getAbsolutePath();
            }

            File stateFile = new File(
                    worldsDirectory,
                    sanitizeFileName(minecraft.world.getWorldInfo().getWorldName()) + ".cfg"
            );
            createDirectory(stateFile.getParentFile());
            return stateFile.getAbsolutePath();
        }

        ServerData serverData = minecraft.getCurrentServerData();
        if (serverData == null || isBlank(serverData.serverIP)) {
            return null;
        }

        String addressKey = getServerAddressKey(serverData.serverIP);
        if (isSavedServerListEntry(minecraft, serverData)) {
            String serverName = isBlank(serverData.serverName) ? "unnamed" : serverData.serverName;
            String fileName = sanitizeFileName(serverName) + "-" + shortHash(serverName + "\n" + addressKey) + ".cfg";
            File stateFile = new File(serverListDirectory, fileName);
            createDirectory(stateFile.getParentFile());
            return stateFile.getAbsolutePath();
        }

        File stateFile = new File(
                serverDirectDirectory,
                "server-" + shortHash(addressKey) + ".cfg"
        );
        createDirectory(stateFile.getParentFile());
        return stateFile.getAbsolutePath();
    }

    private static boolean isSavedServerListEntry(Minecraft minecraft, ServerData currentServer) {
        ServerList serverList = new ServerList(minecraft);

        for (int i = 0; i < serverList.countServers(); i++) {
            ServerData savedServer = serverList.getServerData(i);
            if (savedServer != null
                    && Objects.equals(savedServer.serverName, currentServer.serverName)
                    && Objects.equals(savedServer.serverIP, currentServer.serverIP)) {
                return true;
            }
        }

        return false;
    }

    private static String getContextKey(Minecraft minecraft) {
        if (minecraft.world == null) {
            return "menu";
        }

        if (minecraft.isSingleplayer()) {
            if (minecraft.getIntegratedServer() != null) {
                return "singleplayer:" + minecraft.getIntegratedServer().getFolderName();
            }

            return "singleplayer:" + minecraft.world.getWorldInfo().getWorldName();
        }

        ServerData serverData = minecraft.getCurrentServerData();
        if (serverData == null || isBlank(serverData.serverIP)) {
            return "multiplayer:unknown";
        }

        return "multiplayer:" + serverData.serverName + "\n" + serverData.serverIP;
    }

    private static String getServerAddressKey(String serverAddress) {
        ServerAddress address = ServerAddress.fromString(serverAddress);
        return address.getIP().toLowerCase(Locale.ROOT) + ":" + address.getPort();
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                result.append(String.format("%02x", digest[i] & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String sanitizeFileName(String value) {
        StringBuilder result = new StringBuilder(value.length());

        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < 32 || character == '\\' || character == '/' || character == ':'
                    || character == '*' || character == '?' || character == '"'
                    || character == '<' || character == '>' || character == '|') {
                result.append('_');
            } else {
                result.append(character);
            }
        }

        while (result.length() > 0
                && (result.charAt(result.length() - 1) == '.' || result.charAt(result.length() - 1) == ' ')) {
            result.setLength(result.length() - 1);
        }

        return result.length() == 0 ? "unnamed" : result.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }

    private static void saveIfChanged(Configuration configuration) {
        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
