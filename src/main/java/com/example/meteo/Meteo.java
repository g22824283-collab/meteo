package com.example.meteo;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.minecraft.block.Block;
import net.minecraft.command.CommandSource;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;

public class Meteo implements ModInitializer {
    public static final String MOD_ID = "meteo";
    public static final Identifier RADAR_DATA = new Identifier(MOD_ID, "radar_data");
    public static final Identifier RADAR_REQUEST = new Identifier(MOD_ID, "radar_request");

    public static final Block RADAR = new RadarBlock(FabricBlockSettings.create().strength(3.0f).sounds(BlockSoundGroup.METAL));

    @Override
    public void onInitialize() {
        Identifier id = new Identifier(MOD_ID, "radar");
        Registry.register(Registries.BLOCK, id, RADAR);
        Registry.register(Registries.ITEM, id, new BlockItem(RADAR, new FabricItemSettings()));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(e -> e.add(RADAR));

        ServerTickEvents.END_WORLD_TICK.register(StormManager::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> StormManager.clear());

        ServerPlayNetworking.registerGlobalReceiver(RADAR_REQUEST, (server, player, handler, buf, sender) -> {
            BlockPos pos = buf.readBlockPos();
            server.execute(() -> {
                if (player.getServerWorld().getBlockState(pos).isOf(RADAR)
                        && player.squaredDistanceTo(Vec3d.ofCenter(pos)) < 100) {
                    StormManager.sendRadar(player, pos);
                }
            });
        });

        registerCommands();
    }

    private static int tornadoCmd(CommandContext<ServerCommandSource> ctx, int ef) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        ServerWorld w = p.getServerWorld();
        double yaw = Math.toRadians(p.getYaw());
        double dx = -Math.sin(yaw), dz = Math.cos(yaw);
        StormCell c = StormManager.spawn(w, StormType.SUPERCELL,
                p.getX() + dx * 160, p.getZ() + dz * 160, (float) Math.atan2(-dz, -dx), true);
        int e = ef >= 0 ? ef : StormManager.pickEf(w.random);
        StormManager.beginTornado(w, c, e, 200);
        ctx.getSource().sendFeedback(() -> Text.literal("Суперячейка с торнадо EF" + e), true);
        return 1;
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(CommandManager.literal("storm")
                .requires(s -> s.hasPermissionLevel(2))
                .then(CommandManager.literal("clear").executes(ctx -> {
                    StormManager.cells(ctx.getSource().getWorld()).clear();
                    ctx.getSource().sendFeedback(() -> Text.literal("Все грозы удалены"), true);
                    return 1;
                }))
                .then(CommandManager.literal("tornado")
                    .executes(ctx -> tornadoCmd(ctx, -1))
                    .then(CommandManager.argument("ef", IntegerArgumentType.integer(0, 5))
                        .executes(ctx -> tornadoCmd(ctx, IntegerArgumentType.getInteger(ctx, "ef")))))
                .then(CommandManager.literal("spawn")
                    .then(CommandManager.argument("type", StringArgumentType.word())
                        .suggests((c, b) -> CommandSource.suggestMatching(
                            Arrays.stream(StormType.values()).map(t -> t.name().toLowerCase()), b))
                        .executes(ctx -> {
                            ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                            StormType type;
                            try {
                                type = StormType.valueOf(StringArgumentType.getString(ctx, "type").toUpperCase());
                            } catch (IllegalArgumentException e) {
                                ctx.getSource().sendError(Text.literal("Типы: single_cell, multicell, supercell, squall_line"));
                                return 0;
                            }
                            double yaw = Math.toRadians(p.getYaw());
                            double dx = -Math.sin(yaw), dz = Math.cos(yaw);
                            StormManager.spawn(p.getServerWorld(), type,
                                p.getX() + dx * 160, p.getZ() + dz * 160,
                                (float) Math.atan2(-dz, -dx), true);
                            ctx.getSource().sendFeedback(() -> Text.literal("Появилась: " + type.title), true);
                            return 1;
                        })))));
    }
}
