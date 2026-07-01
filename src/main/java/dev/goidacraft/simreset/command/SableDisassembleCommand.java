package dev.goidacraft.simreset.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.mixinterface.plot.SubLevelContainerHolder;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.holding.SavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;
import dev.ryanhcode.sable.sublevel.storage.region.SubLevelRegionFile;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelStorage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Добавляет в корень /sable подкоманды:
 *   /sable disassemble all | uuid <uuid> | name <name> | nearest | entity <targets>
 *   /sable reassemble  all | uuid <uuid> | name <name> | nearest | entity <targets>
 *
 * nearest       — ближайший к исполнителю команды загруженный саб-левел ("физ. сущность").
 * entity <targets> — саб-левели, в которых физически находятся выбранные сущности.
 *                     <targets> — стандартный ванильный entity-selector (@a, @e, @p, @r, @s
 *                     и любые другие, добавленные другими модами/сервером, например @n).
 * nearest/entity работают только с уже загруженными саб-левелами (без storage-скана),
 * так как сущности физически присутствуют лишь в загруженных чанках.
 *
 * Поддерживает sable 1.2.2+ и 2.0.3+, aeronautics 1.2.1+ и 1.3.0+.
 * Storage-скан незагруженных саблевелов доступен только с sable 2.x.
 */
public class SableDisassembleCommand {

    private static final SimpleCommandExceptionType ERROR_NO_SUBLEVELS =
        new SimpleCommandExceptionType(Component.literal("Не найдено ни одного подходящего sub-level."));

    /**
     * storageFilter — быстрый отсев по данным на диске (UUID / имя из NBT) без загрузки.
     * runtimeFilter — финальная проверка по уже загруженному ServerSubLevel.
     */
    private record SubLevelFilter(
        Predicate<SubLevelData> storageFilter,
        Predicate<ServerSubLevel> runtimeFilter
    ) {}

    // -----------------------------------------------------------------------
    // Registration
    // -----------------------------------------------------------------------

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("sable")
                .requires(src -> src.hasPermission(2))

                .then(Commands.literal("disassemble")
                    .then(Commands.literal("all")
                        .executes(ctx -> executeDisassemble(ctx, filterAll())))
                    .then(Commands.literal("uuid")
                        .then(Commands.argument("uuid", StringArgumentType.string())
                            .suggests(SUGGEST_UUID)
                            .executes(ctx -> executeDisassemble(ctx,
                                filterByUuid(StringArgumentType.getString(ctx, "uuid"))))))
                    .then(Commands.literal("name")
                        .then(Commands.argument("name", StringArgumentType.string())
                            .suggests(SUGGEST_NAME)
                            .executes(ctx -> executeDisassemble(ctx,
                                filterByName(StringArgumentType.getString(ctx, "name"))))))
                    .then(Commands.literal("nearest")
                        .executes(SableDisassembleCommand::executeDisassembleNearest))
                    .then(Commands.literal("entity")
                        .then(Commands.argument("targets", EntityArgument.entities())
                            .executes(ctx -> executeDisassembleTargets(ctx,
                                resolveByEntities(ctx.getSource().getLevel(),
                                    EntityArgument.getEntities(ctx, "targets")))))))

                .then(Commands.literal("reassemble")
                    .then(Commands.literal("all")
                        .executes(ctx -> executeReassemble(ctx, filterAll())))
                    .then(Commands.literal("uuid")
                        .then(Commands.argument("uuid", StringArgumentType.string())
                            .suggests(SUGGEST_UUID)
                            .executes(ctx -> executeReassemble(ctx,
                                filterByUuid(StringArgumentType.getString(ctx, "uuid"))))))
                    .then(Commands.literal("name")
                        .then(Commands.argument("name", StringArgumentType.string())
                            .suggests(SUGGEST_NAME)
                            .executes(ctx -> executeReassemble(ctx,
                                filterByName(StringArgumentType.getString(ctx, "name"))))))
                    .then(Commands.literal("nearest")
                        .executes(SableDisassembleCommand::executeReassembleNearest))
                    .then(Commands.literal("entity")
                        .then(Commands.argument("targets", EntityArgument.entities())
                            .executes(ctx -> executeReassembleTargets(ctx,
                                resolveByEntities(ctx.getSource().getLevel(),
                                    EntityArgument.getEntities(ctx, "targets")))))))
        );
    }

    // -----------------------------------------------------------------------
    // Tab-completion (only loaded sub-levels)
    // -----------------------------------------------------------------------

    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> SUGGEST_UUID =
        (ctx, builder) -> {
            ServerLevel level = ctx.getSource().getLevel();
            if (level instanceof SubLevelContainerHolder holder) {
                List<String> list = new ArrayList<>();
                for (SubLevel sl : holder.sable$getPlotContainer().getAllSubLevels()) {
                    list.add(sl.getUniqueId().toString());
                }
                return net.minecraft.commands.SharedSuggestionProvider.suggest(list, builder);
            }
            return builder.buildFuture();
        };

    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> SUGGEST_NAME =
        (ctx, builder) -> {
            ServerLevel level = ctx.getSource().getLevel();
            if (level instanceof SubLevelContainerHolder holder) {
                List<String> list = new ArrayList<>();
                for (SubLevel sl : holder.sable$getPlotContainer().getAllSubLevels()) {
                    String name = sl.getName();
                    if (name != null && !name.isEmpty()) list.add(name);
                }
                return net.minecraft.commands.SharedSuggestionProvider.suggest(list, builder);
            }
            return builder.buildFuture();
        };

    // -----------------------------------------------------------------------
    // Handlers
    // -----------------------------------------------------------------------

    private static int executeDisassemble(CommandContext<CommandSourceStack> ctx,
                                          SubLevelFilter filter) throws CommandSyntaxException {
        List<ServerSubLevel> targets = findSubLevels(ctx.getSource().getLevel(), filter);
        return executeDisassembleTargets(ctx, targets);
    }

    private static int executeDisassembleNearest(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        ServerSubLevel nearest = resolveNearest(level, ctx.getSource().getPosition());
        return executeDisassembleTargets(ctx, nearest == null ? List.of() : List.of(nearest));
    }

    private static int executeDisassembleTargets(CommandContext<CommandSourceStack> ctx,
                                                  List<ServerSubLevel> targets) throws CommandSyntaxException {
        if (targets.isEmpty()) throw ERROR_NO_SUBLEVELS.create();
        ServerLevel level = ctx.getSource().getLevel();

        int count = doDisassemble(level, targets, false);
        ctx.getSource().sendSuccess(
            () -> Component.literal("[SimReset] Дизассемблировано: " + count), true);
        return count;
    }

    private static int executeReassemble(CommandContext<CommandSourceStack> ctx,
                                         SubLevelFilter filter) throws CommandSyntaxException {
        List<ServerSubLevel> targets = findSubLevels(ctx.getSource().getLevel(), filter);
        return executeReassembleTargets(ctx, targets);
    }

    private static int executeReassembleNearest(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        ServerSubLevel nearest = resolveNearest(level, ctx.getSource().getPosition());
        return executeReassembleTargets(ctx, nearest == null ? List.of() : List.of(nearest));
    }

    private static int executeReassembleTargets(CommandContext<CommandSourceStack> ctx,
                                                 List<ServerSubLevel> targets) throws CommandSyntaxException {
        if (targets.isEmpty()) throw ERROR_NO_SUBLEVELS.create();
        ServerLevel level = ctx.getSource().getLevel();

        // Запоминаем мировые позиции ассемблеров ДО дизассемблирования.
        List<BlockPos> positions = new ArrayList<>();
        java.util.Map<BlockPos, String> namesMap = new java.util.HashMap<>();
        for (ServerSubLevel sl : targets) {
            BlockPos inSL = getAssemblerInSubLevel(sl);
            if (inSL == null) continue;
            BlockPos worldPos = computeGoal(sl, inSL);
            positions.add(worldPos);
            if (sl.getName() != null && !sl.getName().isEmpty()) {
                namesMap.put(worldPos, sl.getName());
            }
        }

        int disassembled = doDisassemble(level, targets, false);

        level.getServer().tell(new net.minecraft.server.TickTask(
            level.getServer().getTickCount() + 1,
            () -> reassemblePositions(level, positions, namesMap)
        ));

        int finalDis = disassembled;
        int finalPend = positions.size();
        ctx.getSource().sendSuccess(() -> Component.literal(
            "[SimReset] Дизассемблировано: " + finalDis +
            ", пересборка " + finalPend + " ед. через 1 тик..."), true);
        return disassembled;
    }

    // -----------------------------------------------------------------------
    // Sub-level discovery
    // -----------------------------------------------------------------------

    /**
     * Phase 1 (все версии sable): уже загруженные через SubLevelContainerHolder.
     * Phase 2 (sable 2.x): незагруженные из storage-файлов — вынесено в addUnloadedSubLevels.
     * При NoClassDefFoundError (sable 1.x) Phase 2 молча пропускается.
     */
    private static List<ServerSubLevel> findSubLevels(ServerLevel level, SubLevelFilter filter) {
        if (!(level instanceof SubLevelContainerHolder holder)) return List.of();
        SubLevelContainer container = holder.sable$getPlotContainer();

        Set<UUID> seen = new HashSet<>();
        List<ServerSubLevel> result = new ArrayList<>();

        // Phase 1
        for (SubLevel sl : new ArrayList<>(container.getAllSubLevels())) {
            if (sl instanceof ServerSubLevel ssl) {
                seen.add(ssl.getUniqueId());
                if (filter.runtimeFilter().test(ssl)) result.add(ssl);
            }
        }

        // Phase 2 — storage-скан работает и в sable 1.x, и в 2.x (API идентичен)
        addUnloadedSubLevels(level, container, filter, seen, result);

        return result;
    }

    /**
     * Загружает незагруженный саб-левел из storage синхронно.
     * sable 2.x: snatchAndLoad — обновляет holdingChunkMap корректно.
     * sable 1.x: SubLevelSerializer.fullyLoad — прямая загрузка без holdingChunkMap.
     */
    private static void forceLoadSubLevel(SubLevelHoldingChunkMap chunkMap,
                                           GlobalSavedSubLevelPointer global,
                                           UUID uuid,
                                           ServerLevel level,
                                           SubLevelData data) {
        try {
            // sable 2.x — snatchAndLoad существует
            java.lang.reflect.Method snatch = chunkMap.getClass()
                .getMethod("snatchAndLoad", GlobalSavedSubLevelPointer.class, UUID.class);
            snatch.invoke(chunkMap, global, uuid);
        } catch (NoSuchMethodException e) {
            // sable 1.x — нет snatchAndLoad, грузим напрямую через SubLevelSerializer
            SubLevelSerializer.fullyLoad(level, data);
        } catch (Exception e) {
            throw new RuntimeException("forceLoadSubLevel failed for " + uuid, e);
        }
    }

    private static void addUnloadedSubLevels(ServerLevel level, SubLevelContainer container,
                                              SubLevelFilter filter, Set<UUID> seen,
                                              List<ServerSubLevel> result) {
        if (!(container instanceof ServerSubLevelContainer sc)) return;

        SubLevelHoldingChunkMap chunkMap = sc.getHoldingChunkMap();
        SubLevelStorage storage = chunkMap.getStorage();
        File[] regionFiles = storage.getFolder().toFile()
            .listFiles((dir, n) -> n.endsWith(SubLevelRegionFile.FILE_EXTENSION));
        if (regionFiles == null) return;

        for (File regionFile : regionFiles) {
            // Формат: "r.REGIONX.REGIONZ.sblvl"
            String[] parts = regionFile.getName().split("\\.");
            if (parts.length < 4) continue;
            int regionX, regionZ;
            try {
                regionX = Integer.parseInt(parts[1]);
                regionZ = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                continue;
            }

            for (int lx = 0; lx < SubLevelRegionFile.SIDE_LENGTH; lx++) {
                for (int lz = 0; lz < SubLevelRegionFile.SIDE_LENGTH; lz++) {
                    ChunkPos chunkPos = new ChunkPos(
                        regionX * SubLevelRegionFile.SIDE_LENGTH + lx,
                        regionZ * SubLevelRegionFile.SIDE_LENGTH + lz);

                    SubLevelHoldingChunk holdingChunk = storage.attemptLoadHoldingChunk(chunkPos);
                    if (holdingChunk == null) continue;

                    for (SavedSubLevelPointer pointer : holdingChunk.getSubLevelPointers()) {
                        SubLevelData data = storage.attemptLoadSubLevel(chunkPos, pointer);
                        if (data == null) continue;

                        UUID uuid = data.uuid();
                        if (seen.contains(uuid)) continue;
                        if (!filter.storageFilter().test(data)) continue;

                        // Принудительная синхронная загрузка (version-aware)
                        GlobalSavedSubLevelPointer global =
                            new GlobalSavedSubLevelPointer(chunkPos, pointer.storageIndex(), pointer.subLevelIndex());
                        forceLoadSubLevel(chunkMap, global, uuid, level, data);
                        seen.add(uuid);

                        // Найти только что загруженный саблевел
                        for (SubLevel sl : sc.getAllSubLevels()) {
                            if (sl instanceof ServerSubLevel ssl
                                    && uuid.equals(ssl.getUniqueId())
                                    && filter.runtimeFilter().test(ssl)) {
                                result.add(ssl);
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Ближайший к точке загруженный саб-левел ("физ. сущность").
     * Незагруженные (storage) саб-левели намеренно не сканируются: они не присутствуют
     * физически рядом с исполнителем, пока не подгружены.
     */
    private static ServerSubLevel resolveNearest(ServerLevel level, Vec3 origin) {
        if (!(level instanceof SubLevelContainerHolder holder)) return null;

        ServerSubLevel nearest = null;
        double bestDistSq = Double.MAX_VALUE;
        for (SubLevel sl : holder.sable$getPlotContainer().getAllSubLevels()) {
            if (!(sl instanceof ServerSubLevel ssl)) continue;
            Vector3dc center = ssl.boundingBox().center();
            double dx = center.x() - origin.x;
            double dy = center.y() - origin.y;
            double dz = center.z() - origin.z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                nearest = ssl;
            }
        }
        return nearest;
    }

    /**
     * Саб-левели, чей мировой bounding box пересекается с положением одной из выбранных
     * (через vanilla entity-selector, например @a/@e/@p/@r/@s/@n) сущностей.
     */
    private static List<ServerSubLevel> resolveByEntities(ServerLevel level, Collection<? extends Entity> entities) {
        if (!(level instanceof SubLevelContainerHolder holder) || entities.isEmpty()) return List.of();
        SubLevelContainer container = holder.sable$getPlotContainer();

        Set<ServerSubLevel> matched = new LinkedHashSet<>();
        for (Entity entity : entities) {
            BoundingBox3d probe = new BoundingBox3d(entity.getBoundingBox()).expand(0.5);
            for (SubLevel sl : container.queryIntersecting(probe)) {
                if (sl instanceof ServerSubLevel ssl) matched.add(ssl);
            }
        }
        return new ArrayList<>(matched);
    }

    // -----------------------------------------------------------------------
    // Disassemble / reassemble
    // -----------------------------------------------------------------------

    private static int doDisassemble(ServerLevel level, List<ServerSubLevel> targets,
                                     boolean playSound) {
        int count = 0;
        for (ServerSubLevel sl : targets) {
            BlockPos assemblerInSL = getAssemblerInSubLevel(sl);
            if (assemblerInSL == null) {
                level.getServer().sendSystemMessage(Component.literal(
                    "[SimReset] Sub-level " + sl.getUniqueId() + " пропущен: нет primary assembler."));
                continue;
            }
            try {
                Rotation rotation = computeRotation(sl);
                BlockPos goal    = computeGoal(sl, assemblerInSL);
                Class<?> helper  = Class.forName("dev.simulated_team.simulated.util.SimAssemblyHelper");
                callDisassemble(helper, level, sl, assemblerInSL, goal, rotation, playSound);
                count++;
            } catch (Exception e) {
                level.getServer().sendSystemMessage(Component.literal(
                    "[SimReset] Ошибка дизассемблирования " + sl.getUniqueId() + ": " + e.getMessage()));
            }
        }
        return count;
    }

    /**
     * Пробует подпись с 6 параметрами (aeronautics 1.3.x),
     * при NoSuchMethodException — подпись с 5 параметрами (aeronautics 1.2.x, без флага звука).
     */
    private static void callDisassemble(Class<?> helper, net.minecraft.world.level.Level level,
                                        SubLevel sl, BlockPos anchor, BlockPos goal,
                                        Rotation rotation, boolean playSound) throws Exception {
        try {
            helper.getMethod("disassembleSubLevel",
                net.minecraft.world.level.Level.class, SubLevel.class,
                BlockPos.class, BlockPos.class, Rotation.class, boolean.class)
                .invoke(null, level, sl, anchor, goal, rotation, playSound);
        } catch (NoSuchMethodException e) {
            // aeronautics 1.2.x — без параметра звука
            helper.getMethod("disassembleSubLevel",
                net.minecraft.world.level.Level.class, SubLevel.class,
                BlockPos.class, BlockPos.class, Rotation.class)
                .invoke(null, level, sl, anchor, goal, rotation);
        }
    }

    private static void reassemblePositions(ServerLevel level, List<BlockPos> positions,
                                            java.util.Map<BlockPos, String> namesMap) {
        if (!(level instanceof SubLevelContainerHolder holder)) return;
        SubLevelContainer container = holder.sable$getPlotContainer();

        int ok = 0, fail = 0;
        for (BlockPos pos : positions) {
            net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
            if (be == null || !be.getClass().getName().endsWith("PhysicsAssemblerBlockEntity")) {
                level.getServer().sendSystemMessage(Component.literal(
                    "[SimReset] Ассемблер не найден на " + pos + " — пропуск."));
                fail++;
                continue;
            }
            try {
                List<SubLevel> before = new ArrayList<>(container.getAllSubLevels());
                be.getClass().getMethod("assembleOrDisassemble").invoke(be);
                List<SubLevel> after = new ArrayList<>(container.getAllSubLevels());
                after.removeAll(before);

                if (!after.isEmpty() && namesMap.containsKey(pos)) {
                    ((ServerSubLevel) after.get(0)).setName(namesMap.get(pos));
                }
                ok++;
            } catch (Exception e) {
                level.getServer().sendSystemMessage(Component.literal(
                    "[SimReset] Ошибка сборки на " + pos + ": " + e.getMessage()));
                fail++;
            }
        }
        level.getServer().sendSystemMessage(Component.literal(
            "[SimReset] Пересборка завершена. Успешно: " + ok + ", ошибок: " + fail + "."));
    }

    // -----------------------------------------------------------------------
    // Assembler lookup
    // -----------------------------------------------------------------------

    /**
     * Возвращает позицию primary assembler внутри sub-level.
     * 1. Пробует simulated$getPrimaryAssembler (aeronautics 1.3.x mixin).
     * 2. Если null — сканирует блоки sub-level'а (совместимость с aeronautics 1.2.x).
     */
    private static BlockPos getAssemblerInSubLevel(ServerSubLevel sl) {
        try {
            java.lang.reflect.Method m = sl.getClass().getMethod("simulated$getPrimaryAssembler");
            BlockPos pos = (BlockPos) m.invoke(sl);
            if (pos != null) return pos;
        } catch (Exception ignored) {}
        return scanForPrimaryAssembler(sl);
    }

    private static BlockPos scanForPrimaryAssembler(ServerSubLevel sl) {
        try {
            net.minecraft.world.level.Level slLevel = sl.getLevel();
            var bb = sl.getPlot().getBoundingBox();
            for (int x = bb.minX(); x <= bb.maxX(); x++) {
                for (int y = bb.minY(); y <= bb.maxY(); y++) {
                    for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        var be = slLevel.getBlockEntity(pos);
                        if (be == null) continue;
                        if (!be.getClass().getSimpleName().equals("PhysicsAssemblerBlockEntity")) continue;
                        if (isPrimaryAssembler(be, slLevel)) return pos;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Проверяет флаг primaryAssembler через поле (быстро) или через NBT (fallback). */
    private static boolean isPrimaryAssembler(
            net.minecraft.world.level.block.entity.BlockEntity be,
            net.minecraft.world.level.Level slLevel) {
        // Быстрый путь: прямой доступ к полю
        java.lang.reflect.Field field = findDeclaredField(be.getClass(), "primaryAssembler");
        if (field != null) {
            try {
                field.setAccessible(true);
                return (boolean) field.get(be);
            } catch (Exception ignored) {}
        }
        // Медленный путь: читаем NBT через публичный saveWithId
        try {
            var tag = be.saveWithId(slLevel.registryAccess());
            return tag.getBoolean("IsPrimary");
        } catch (Exception ignored) {}
        return false;
    }

    private static java.lang.reflect.Field findDeclaredField(Class<?> clazz, String name) {
        while (clazz != null) {
            try { return clazz.getDeclaredField(name); }
            catch (NoSuchFieldException e) { clazz = clazz.getSuperclass(); }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Math helpers
    // -----------------------------------------------------------------------

    private static Rotation computeRotation(ServerSubLevel sl) {
        Quaterniondc q = sl.logicalPose().orientation();
        Vector3d euler = new Vector3d();
        q.getEulerAnglesYXZ(euler);
        double yaw = euler.y;
        double ninety = Math.PI / 2.0;
        int turns = -(Mth.floor((float) (yaw / ninety + 0.5)));
        return switch (Math.floorMod(turns, 4)) {
            case 0 -> Rotation.NONE;
            case 1 -> Rotation.COUNTERCLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.CLOCKWISE_90;
            default -> throw new AssertionError();
        };
    }

    private static BlockPos computeGoal(ServerSubLevel sl, BlockPos assemblerInSL) {
        return BlockPos.containing(
            sl.logicalPose().transformPosition(Vec3.atCenterOf(assemblerInSL)));
    }

    // -----------------------------------------------------------------------
    // Filter factories
    // -----------------------------------------------------------------------

    private static SubLevelFilter filterAll() {
        return new SubLevelFilter(data -> true, sl -> true);
    }

    private static SubLevelFilter filterByUuid(String raw) {
        UUID target;
        try { target = UUID.fromString(raw); }
        catch (IllegalArgumentException e) {
            return new SubLevelFilter(data -> false, sl -> false);
        }
        return new SubLevelFilter(
            data -> target.equals(data.uuid()),
            sl   -> target.equals(sl.getUniqueId())
        );
    }

    private static SubLevelFilter filterByName(String name) {
        return new SubLevelFilter(
            data -> {
                var tag = data.fullTag();
                return tag.contains("display_name") && name.equals(tag.getString("display_name"));
            },
            sl -> name.equals(sl.getName())
        );
    }
}
