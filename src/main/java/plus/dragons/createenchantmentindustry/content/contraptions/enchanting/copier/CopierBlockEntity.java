package plus.dragons.createenchantmentindustry.content.contraptions.enchanting.copier;

import com.simibubi.create.content.contraptions.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.contraptions.relays.belt.transport.TransportedItemStack;
import com.simibubi.create.foundation.advancement.AdvancementBehaviour;
import com.simibubi.create.foundation.tileEntity.SmartTileEntity;
import com.simibubi.create.foundation.tileEntity.TileEntityBehaviour;
import com.simibubi.create.foundation.tileEntity.behaviour.belt.BeltProcessingBehaviour;
import com.simibubi.create.foundation.tileEntity.behaviour.belt.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.foundation.tileEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.simibubi.create.foundation.utility.VecHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.WrittenBookItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import plus.dragons.createdragonlib.mixin.AdvancementBehaviourAccessor;
import plus.dragons.createdragonlib.utility.ModLang;
import plus.dragons.createenchantmentindustry.EnchantmentIndustry;
import plus.dragons.createenchantmentindustry.foundation.config.ModConfigs;
import plus.dragons.createenchantmentindustry.foundation.data.advancement.CeiAdvancements;

import java.util.ArrayList;
import java.util.List;

import static com.simibubi.create.foundation.tileEntity.behaviour.belt.BeltProcessingBehaviour.ProcessingResult.HOLD;
import static com.simibubi.create.foundation.tileEntity.behaviour.belt.BeltProcessingBehaviour.ProcessingResult.PASS;

public class CopierBlockEntity extends SmartTileEntity implements IHaveGoggleInformation {

    public static final int COPYING_TIME = 100;
    protected BeltProcessingBehaviour beltProcessing;
    public int processingTicks;
    SmartFluidTankBehaviour tank;
    public ItemStack copyTarget;
    public boolean tooExpensive;
    boolean sendParticles;

    public CopierBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        processingTicks = -1;
        copyTarget = null;
        tooExpensive = false;
    }

    @Override
    public void addBehaviours(List<TileEntityBehaviour> behaviours) {
        behaviours.add(tank = SmartFluidTankBehaviour.single(this, ModConfigs.SERVER.copierTankCapacity.get()));
        behaviours.add(beltProcessing = new BeltProcessingBehaviour(this).whenItemEnters(this::onItemReceived)
                .whileItemHeld(this::whenItemHeld));
        registerAwardables(behaviours,
                CeiAdvancements.COPIABLE_MASTERPIECE.asCreateAdvancement(),
                CeiAdvancements.COPIABLE_MYSTERY.asCreateAdvancement(),
                CeiAdvancements.RELIC_RESTORATION.asCreateAdvancement());
    }

    public void tick() {
        super.tick();

        if (processingTicks >= 0) {
            processingTicks--;
        }
    }

    protected static int ENCHANT_PARTICLE_COUNT = 20;

    protected void spawnParticles() {
        if (isVirtual())
            return;
        Vec3 vec = VecHelper.getCenterOf(worldPosition);
        vec = vec.subtract(0, 11 / 16f, 0);
        ParticleOptions particle = ParticleTypes.ENCHANT;
        for (int i = 0; i < ENCHANT_PARTICLE_COUNT; i++) {
            Vec3 m = VecHelper.offsetRandomly(Vec3.ZERO, level.random, 1f);
            m = new Vec3(m.x, Math.abs(m.y), m.z);
            level.addAlwaysVisibleParticle(particle, vec.x, vec.y, vec.z, m.x, m.y, m.z);
        }
        level.playLocalSound(vec.x, vec.y, vec.z, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 1f, level.random.nextFloat() * .1f + .9f, true);
    }

    protected BeltProcessingBehaviour.ProcessingResult onItemReceived(TransportedItemStack transported,
                                                                      TransportedItemStackHandlerBehaviour handler) {
        if (handler.tileEntity.isVirtual())
            return PASS;
        if (tooExpensive || copyTarget == null)
            return PASS;
        if (!CopyingBook.valid(transported.stack))
            return PASS;
        if (tank.isEmpty() || CopyingBook.isCorrectInt(copyTarget, getCurrentFluidInTank()))
            return HOLD;
        if (CopyingBook.getRequiredAmountForItem(copyTarget) == -1)
            return PASS;
        return HOLD;
    }

    protected BeltProcessingBehaviour.ProcessingResult whenItemHeld(TransportedItemStack transported,
                                                                    TransportedItemStackHandlerBehaviour handler) {
        if (processingTicks != -1 && processingTicks != 10)
            return HOLD;
        if (tooExpensive || copyTarget == null)
            return PASS;
        if (!CopyingBook.valid(transported.stack))
            return PASS;
        if (tank.isEmpty() || !CopyingBook.isCorrectInt(copyTarget, getCurrentFluidInTank()))
            return HOLD;
        FluidStack fluid = getCurrentFluidInTank();
        int requiredAmountForItem = CopyingBook.getRequiredAmountForItem(copyTarget);
        if (requiredAmountForItem == -1)
            return PASS;
        if (requiredAmountForItem > fluid.getAmount())
            return HOLD;

        if (processingTicks == -1) {
            processingTicks = COPYING_TIME;
            notifyUpdate();
            return HOLD;
        }

        // Award Advancement
        var item = copyTarget.copy();
        if (!level.isClientSide()) {
            if (item.is(Items.WRITTEN_BOOK)) {
                award(CeiAdvancements.COPIABLE_MASTERPIECE.asCreateAdvancement());
                if (item.getOrCreateTag().getInt("generation") == 3)
                    award(CeiAdvancements.RELIC_RESTORATION.asCreateAdvancement());
            } else award(CeiAdvancements.COPIABLE_MYSTERY.asCreateAdvancement());
            var advancementBehaviour = getBehaviour(AdvancementBehaviour.TYPE);
            var playerId = ((AdvancementBehaviourAccessor) advancementBehaviour).getPlayerId();
            if (playerId != null) {
                var player = level.getPlayerByUUID(playerId);
                CeiAdvancements.Triggers.BOOK_PRINTED.trigger(player, 1);
            }
        }

        // Process finished
        ItemStack copy = CopyingBook.print(copyTarget, requiredAmountForItem, transported.stack, fluid);
        List<TransportedItemStack> outList = new ArrayList<>();
        TransportedItemStack held = null;
        TransportedItemStack result = transported.copy();
        result.stack = copy;
        if (!transported.stack.isEmpty())
            held = transported.copy();
        outList.add(result);
        handler.handleProcessingOnItem(transported, TransportedItemStackHandlerBehaviour.TransportedResult.convertToAndLeaveHeld(outList, held));
        tank.getPrimaryHandler().setFluid(fluid);
        sendParticles = true;
        notifyUpdate();
        return HOLD;
    }

    private FluidStack getCurrentFluidInTank() {
        return tank.getPrimaryHandler()
                .getFluid();
    }

    @Override
    protected void write(CompoundTag compoundTag, boolean clientPacket) {
        super.write(compoundTag, clientPacket);
        compoundTag.putInt("ProcessingTicks", processingTicks);
        compoundTag.putBoolean("tooExpensive", tooExpensive);
        if (copyTarget != null)
            compoundTag.put("copyTarget", copyTarget.serializeNBT());
        if (sendParticles && clientPacket) {
            compoundTag.putBoolean("SpawnParticles", true);
            sendParticles = false;
        }
    }

    @Override
    protected void read(CompoundTag compoundTag, boolean clientPacket) {
        super.read(compoundTag, clientPacket);
        copyTarget = null;
        processingTicks = compoundTag.getInt("ProcessingTicks");
        tooExpensive = compoundTag.getBoolean("tooExpensive");
        if (compoundTag.contains("copyTarget"))
            copyTarget = ItemStack.of(compoundTag.getCompound("copyTarget"));
        if (!clientPacket)
            return;
        if (compoundTag.contains("SpawnParticles"))
            spawnParticles();
    }

    @Override
    @NotNull
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
        if (cap == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY && side != Direction.DOWN)
            return tank.getCapability()
                    .cast();
        return super.getCapability(cap, side);
    }

    @Override
    protected AABB createRenderBoundingBox() {
        return super.createRenderBoundingBox().expandTowards(0, -2, 0);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        var modid = EnchantmentIndustry.MOD_ID;
        ModLang.translate(modid, "gui.goggles.copier_machine").forGoggles(tooltip);
        if (copyTarget == null) {
            ModLang.translate(modid, "gui.goggles.copier_no_target")
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip, 1);
        } else {
            if (copyTarget.is(Items.WRITTEN_BOOK)) {
                var page = WrittenBookItem.getPageCount(copyTarget);
                var b = ModLang.builder(modid)
                        .add(ModLang.itemName(modid, copyTarget)
                                .style(ChatFormatting.BLUE))
                        .text(ChatFormatting.GRAY, " / ")
                        .add(ModLang.number(modid, page)
                                .text(" ")
                                .add(page == 1 ? ModLang.translate(modid, "generic.unit.page") : ModLang.translate(modid, "generic.unit.pages"))
                                .style(ChatFormatting.DARK_GRAY));
                b.forGoggles(tooltip, 1);
                if (CopyingBook.isTooExpensive(copyTarget, ModConfigs.SERVER.copierTankCapacity.get()))
                    tooltip.add(Component.literal("     ").append(ModLang.translate(modid,
                            "gui.goggles.too_expensive").component()
                    ).withStyle(ChatFormatting.RED));
                else
                    tooltip.add(Component.literal("     ").append(ModLang.translate(
                            "gui.goggles.ink_consumption",
                            String.valueOf(CopyingBook.getExperienceFromItem(copyTarget))).component()
                    ).withStyle(ChatFormatting.DARK_GRAY));
            } else if (copyTarget.is(Items.ENCHANTED_BOOK)) {
                var b = ModLang.itemName(modid, copyTarget).style(ChatFormatting.LIGHT_PURPLE);
                b.forGoggles(tooltip, 1);
                boolean tooExpensive = CopyingBook.isTooExpensive(copyTarget, ModConfigs.SERVER.copierTankCapacity.get());
                if (tooExpensive)
                    tooltip.add(Component.literal("     ").append(ModLang.translate(modid,
                            "gui.goggles.too_expensive").component()
                    ).withStyle(ChatFormatting.RED));
                else
                    tooltip.add(Component.literal("     ").append(ModLang.translate(
                            "gui.goggles.xp_consumption",
                            String.valueOf(CopyingBook.getExperienceFromItem(copyTarget))).component()
                    ).withStyle(ChatFormatting.GREEN));
                var map = EnchantmentHelper.getEnchantments(copyTarget);
                for (var e : map.entrySet()) {
                    Component name = e.getKey().getFullname(e.getValue());
                    tooltip.add(Component.literal("     ").append(name).withStyle(name.getStyle()));
                }
            }
        }
        containedFluidTooltip(tooltip, isPlayerSneaking, getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY));
        return true;
    }

}
