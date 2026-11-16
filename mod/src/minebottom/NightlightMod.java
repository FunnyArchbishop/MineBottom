package minebottom;

import com.google.gson.Gson;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public final class NightlightMod implements ClientModInitializer {
    private record Binding(String dimension, int x, int y, int z) {
        BlockPos position() { return new BlockPos(x, y, z); }
    }

    private static final Gson JSON = new Gson();
    private SerialRelay relay;
    private ClientPacketListener connection;
    private Path bindingFile;
    private Binding binding;
    private String observation = "";

    @Override
    public void onInitializeClient() {
        relay = new SerialRelay();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
            dispatcher.register(command("nightlight").requires(FabricClientCommandSource::attended)
                .then(command("bind").executes(ctx -> bind(ctx.getSource())))
                .then(command("unbind").executes(ctx -> unbind(ctx.getSource())))
                .then(command("status").executes(ctx -> status(ctx.getSource())))
                .then(command("test")
                    .then(command("on").executes(ctx -> test(ctx.getSource(), true)))
                    .then(command("off").executes(ctx -> test(ctx.getSource(), false))))));
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> reset());
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> command(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private void reset() {
        relay.setTarget(null);
        connection = null;
        bindingFile = null;
        binding = null;
        observation = "";
    }

    private static Path bindingPath(Minecraft client) {
        var localServer = client.getSingleplayerServer();
        if (localServer != null) {
            return localServer.getWorldPath(LevelResource.ROOT).resolve("minebottom-lever.json");
        }
        var remoteServer = client.getCurrentServer();
        if (remoteServer == null || remoteServer.ip == null || remoteServer.ip.isBlank()) return null;
        // Use the address entered in the multiplayer list, independent of DNS changes.
        String address = remoteServer.ip.strip().toLowerCase(Locale.ROOT);
        var id = UUID.nameUUIDFromBytes(address.getBytes(StandardCharsets.UTF_8));
        return FabricLoader.getInstance().getConfigDir().resolve("minebottom-servers")
            .resolve(id + ".json");
    }

    private void refresh(Minecraft client) {
        var next = client.getConnection();
        if (next == connection) return;
        reset();
        if (next == null) return;
        Path file = bindingPath(client);
        if (file == null) return; // The server address may not be available during join.
        connection = next;
        bindingFile = file;
        if (!Files.exists(file)) return;
        try {
            Binding saved = JSON.fromJson(Files.readString(file), Binding.class);
            if (saved != null && saved.dimension() != null) binding = saved;
        } catch (IOException | RuntimeException e) {
            observation = "无法读取绑定";
            System.err.println("MineBottom: could not read binding: " + e);
        }
    }

    private void save(Binding next) throws IOException {
        if (next == null) {
            Files.deleteIfExists(bindingFile);
        } else {
            Files.createDirectories(bindingFile.getParent());
            Path temporary = bindingFile.resolveSibling(bindingFile.getFileName() + ".tmp");
            Files.writeString(temporary, JSON.toJson(next));
            Files.move(temporary, bindingFile, StandardCopyOption.REPLACE_EXISTING);
        }
        binding = next;
    }

    private int bind(FabricClientCommandSource source) {
        Minecraft client = source.getClient();
        refresh(client);
        if (bindingFile == null || client.level == null)
            return fail(source, "请先进入世界。多人游戏请通过服务器地址加入。");
        if (!(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK)
            return fail(source, "请把准星对着要绑定的拉杆。");
        BlockPos position = hit.getBlockPos();
        var state = client.level.getBlockState(position);
        if (!state.is(Blocks.LEVER)) return fail(source, "准星指向的方块不是原版拉杆。");
        try {
            save(new Binding(dimension(client.level), position.getX(), position.getY(), position.getZ()));
        } catch (IOException e) {
            return fail(source, "无法保存绑定：" + e.getMessage());
        }
        tick(client);
        return status(source);
    }

    private int unbind(FabricClientCommandSource source) {
        refresh(source.getClient());
        if (bindingFile == null) return fail(source, "当前连接没有可用的存档或服务器地址。");
        try {
            save(null);
        } catch (IOException e) {
            return fail(source, "无法清除绑定：" + e.getMessage());
        }
        relay.setTarget(null);
        observation = "";
        source.sendFeedback(Component.literal("已解绑，已请求关灯。"));
        return 1;
    }

    private int test(FabricClientCommandSource source, boolean on) {
        refresh(source.getClient());
        if (bindingFile == null) return fail(source, "当前连接没有可用的存档或服务器地址。");
        if (binding != null) return fail(source, "请先解绑拉杆再手动测试。");
        relay.setTarget(on);
        source.sendFeedback(Component.literal(on ? "已请求开灯。" : "已请求关灯。"));
        return 1;
    }

    private int status(FabricClientCommandSource source) {
        refresh(source.getClient());
        var location = binding == null ? "未绑定" : "绑定：" + binding.dimension()
            + " " + binding.x() + " " + binding.y() + " " + binding.z();
        String visibility = observation.isEmpty() ? "" : " | " + observation;
        source.sendFeedback(Component.literal(location + visibility + " | " + relay.status()));
        return 1;
    }

    private void tick(Minecraft client) {
        refresh(client);
        if (binding == null) return;
        var level = client.level;
        var position = binding.position();
        if (level == null || !dimension(level).equals(binding.dimension())
                || !level.hasChunk(position.getX() >> 4, position.getZ() >> 4)) {
            observation = "绑定位置未加载";
            relay.setTarget(null);
            return;
        }
        var state = level.getBlockState(position);
        if (!state.is(Blocks.LEVER)) {
            observation = "绑定位置没有拉杆";
            relay.setTarget(null);
            return;
        }
        boolean powered = state.getValue(LeverBlock.POWERED);
        observation = powered ? "拉杆已通电" : "拉杆未通电";
        relay.setTarget(powered);
    }

    private static String dimension(Level level) {
        return level.dimension().identifier().toString();
    }

    private static int fail(FabricClientCommandSource source, String message) {
        source.sendError(Component.literal(message));
        return 0;
    }
}
