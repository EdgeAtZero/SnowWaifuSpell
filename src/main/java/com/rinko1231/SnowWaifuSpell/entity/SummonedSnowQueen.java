package com.rinko1231.SnowWaifuSpell.entity;

import com.rinko1231.SnowWaifuSpell.ai.FlyingFollowOwnerGoal;
import com.rinko1231.SnowWaifuSpell.ai.NewHoverBeamGoal;
import com.rinko1231.SnowWaifuSpell.ai.NewSitWhenOrderedToGoal;
import com.rinko1231.SnowWaifuSpell.init.ModEntityRegistry;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.ICastData;
import io.redspace.ironsspellbooks.api.util.Utils;
import io.redspace.ironsspellbooks.capabilities.magic.MagicManager;
import io.redspace.ironsspellbooks.capabilities.magic.SummonManager;
import io.redspace.ironsspellbooks.damage.SpellDamageSource;
import io.redspace.ironsspellbooks.entity.mobs.IMagicSummon;
import io.redspace.ironsspellbooks.entity.mobs.goals.*;
import io.redspace.ironsspellbooks.entity.spells.AbstractConeProjectile;
import io.redspace.ironsspellbooks.entity.spells.cone_of_cold.ConeOfColdProjectile;
import io.redspace.ironsspellbooks.entity.spells.ray_of_frost.RayOfFrostVisualEntity;
import io.redspace.ironsspellbooks.entity.spells.snowball.Snowball;
import io.redspace.ironsspellbooks.spells.EntityCastData;
import io.redspace.ironsspellbooks.util.ParticleHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import twilightforest.init.TFParticleType;
import twilightforest.init.TFSounds;

import javax.annotation.Nullable;
import java.util.List;

public class SummonedSnowQueen extends TamableMob implements IMagicSummon {
    private static final EntityDataAccessor<Boolean> BEAM_FLAG;
    private static final EntityDataAccessor<Integer> QUEEN_LEVEL =
            SynchedEntityData.defineId(SummonedSnowQueen.class, EntityDataSerializers.INT);
    private static final int SNOWBALL_INTERVAL = 100; // 5 秒
    private static final int ICE_SPIKE_INTERVAL = 160; // 8 秒
    private static final int BREATH_INTERVAL = 60;
    static {
        BEAM_FLAG = SynchedEntityData.defineId(SummonedSnowQueen.class, EntityDataSerializers.BOOLEAN);
    }

    // 冷却字段
    private int breathCooldown = 0;       // 喷雾冷却计时
    private boolean isBreathingPhase = false;
    private int snowballCooldown = 0;     // 雪球冷却计时
    private int iceSpikeCooldown = 0;     // 冰锥冷却计时

    public SummonedSnowQueen(EntityType<? extends SummonedSnowQueen> type, Level level) {
        super(type, level);
        this.xpReward = 0;
        //this.setNoGravity(true);
        this.moveControl = new FlyingMoveControl(this, 10, true);
    }

    public SummonedSnowQueen(Level level, LivingEntity owner) {
        this(ModEntityRegistry.SUMMONED_SNOW_QUEEN.get(), level);
        this.setSummoner(owner);
        this.setOwnerUUID(owner.getUUID());
        //this.setNoGravity(true);
        this.moveControl = new FlyingMoveControl(this, 10, true);
    }

    public int getQueenLevel() {
        return this.entityData.get(QUEEN_LEVEL);
    }

    public void setQueenLevel(int level) {
        this.entityData.set(QUEEN_LEVEL, level);
    }

    public boolean isAlliedTo(Entity pEntity) {
        return super.isAlliedTo(pEntity) || this.isAlliedHelper(pEntity) || pEntity == this.getSummoner();
    }

    public void setSummoner(@Nullable LivingEntity owner) {
        if (owner != null) {
            SummonManager.setOwner(this, owner);
        }
    }



    @Override
    public boolean hurt(@NotNull DamageSource pSource, float pAmount) {
        return !this.shouldIgnoreDamage(pSource) && super.hurt(pSource, pAmount);
    }

    private void handleCombatAI() {
        LivingEntity target = this.getTarget();
        boolean hasTarget = target != null && target.isAlive();

        if (hasTarget) {
            // ===== 喷雾逻辑 =====
            if (breathCooldown > 0) {
                breathCooldown--;
            } else {
                // 切换喷雾状态
                isBreathingPhase = !isBreathingPhase;
                setBreathing(isBreathingPhase);
                breathCooldown = BREATH_INTERVAL; // 每阶段 3 秒
            }

            // 喷雾阶段：定期造成伤害
            if (isBreathingPhase && this.tickCount % 10 == 0) {
                doBreathAttack();
            }

            // ===== 雪球逻辑 =====
            if (!isBreathingPhase) { // 避免喷雾和雪球同时释放
                if (snowballCooldown > 0) {
                    snowballCooldown--;
                } else {
                    castSnowball(target);
                    snowballCooldown = SNOWBALL_INTERVAL;
                }
            }

            // ===== 冰锥逻辑 =====
            if (!isBreathingPhase) { // 避免喷雾和冰锥同时释放
                if (iceSpikeCooldown > 0) {
                    iceSpikeCooldown--;
                } else {
                    castIceRay();
                    iceSpikeCooldown = ICE_SPIKE_INTERVAL;
                }
            }

        } else {
            // 没有目标 → 停止技能，重置状态
            isBreathingPhase = false;
            setBreathing(false);
            breathCooldown = 0;
            snowballCooldown = 0;
            iceSpikeCooldown = 0;
        }
    }



    @Override
    protected SoundEvent getAmbientSound() {
        return TFSounds.SNOW_QUEEN_AMBIENT.get();
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return TFSounds.SNOW_QUEEN_HURT.get();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return TFSounds.SNOW_QUEEN_DEATH.get();
    }
    @Override
    public void tick() {
        super.tick();


        if (this.deathTime > 0) {
            for (int i = 0; i < 5; ++i) {
                double d = this.getRandom().nextGaussian() * 0.02;
                double d1 = this.getRandom().nextGaussian() * 0.02;
                double d2 = this.getRandom().nextGaussian() * 0.02;
                this.level().addParticle(
                        this.getRandom().nextBoolean() ? ParticleTypes.EXPLOSION : ParticleTypes.POOF,
                        this.getX() + (this.getRandom().nextFloat() * this.getBbWidth() * 2.0F) - this.getBbWidth(),
                        this.getY() + (this.getRandom().nextFloat() * this.getBbHeight()),
                        this.getZ() + (this.getRandom().nextFloat() * this.getBbWidth() * 2.0F) - this.getBbWidth(),
                        d, d1, d2
                );
            }
        }
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if(this.isInSittingPose()) return;
        // 缓慢下落
        if (!this.onGround() && this.getDeltaMovement().y < 0.0D) {
            this.setDeltaMovement(this.getDeltaMovement().multiply(1.0D, 0.6D, 1.0D));
        }

        // 落地缓冲：防止接触地面时被弹飞
        if (this.onGround() && Math.abs(this.getDeltaMovement().y) < 0.05D) {
            this.setDeltaMovement(this.getDeltaMovement().x, 0.0D, this.getDeltaMovement().z);
        }
        // AI 行为部分（喷雾切换 / 技能释放）
        if (!level().isClientSide) {
            handleCombatAI();
        }


        // 客户端绘制粒子效果（包括喷雾）
        if (this.level().isClientSide()) {
            this.spawnParticles();
        }
    }

    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack itemstack = player.getItemInHand(hand);

        // 只允许主人控制
        if (this.getSummoner()==player && !this.level().isClientSide) {
            // 切换坐下状态
            this.setOrderedToSit(!this.isOrderedToSit());

            // 停止移动和攻击
            this.jumping = false;
            this.getNavigation().stop();
            this.setTarget(null);
            this.setDeltaMovement(Vec3.ZERO); // 停止当前运动

            // 坐下时同步坐姿
            this.setInSittingPose(this.isOrderedToSit());

            return InteractionResult.SUCCESS;
        }

        return super.mobInteract(player, hand);
    }

    @Override
    protected void registerGoals() {
this.goalSelector.addGoal(1, new NewSitWhenOrderedToGoal(this));
        this.goalSelector.addGoal(2, new NewHoverBeamGoal(this, 20));
        this.goalSelector.addGoal(5, new MeleeAttackGoal(this, 1.0, true));
        //this.goalSelector.addGoal(7, new GenericFollowOwnerGoal(this, this::getSummoner, (double) 1.5F, 15.0F, 4.0F, true, 25.0F));
        this.goalSelector.addGoal(7, new FlyingFollowOwnerGoal(this, this::getSummoner,
                1.5, 15.0F, 4.0F, 30.0F));
        this.goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 0.8));
        this.goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 3.0F, 1.0F));
        this.goalSelector.addGoal(10, new LookAtPlayerGoal(this, Mob.class, 8.0F));
        this.targetSelector.addGoal(1, new GenericOwnerHurtByTargetGoal(this, this::getSummoner));
        this.targetSelector.addGoal(2, new GenericOwnerHurtTargetGoal(this, this::getSummoner));
        this.targetSelector.addGoal(3, new GenericCopyOwnerTargetGoal(this, this::getSummoner));
        this.targetSelector.addGoal(4, (new GenericHurtByTargetGoal(this, (entity) -> entity == this.getSummoner())).setAlertOthers());
        this.targetSelector.addGoal(5, new GenericProtectOwnerTargetGoal(this, this::getSummoner));

    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(BEAM_FLAG, false);
        builder.define(QUEEN_LEVEL, 1); // 默认1级

    }
    @Override
    protected int calculateFallDamage(float p_21237_, float p_21238_) {
        return 0;
    }

    public void doBreathAttack() {
        if (!this.level().isClientSide) {
            // 检查场上是否已有锥形体（防止每 tick 生成新的）
            List<ConeOfColdProjectile> existing = this.level().getEntitiesOfClass(
                    ConeOfColdProjectile.class,
                    this.getBoundingBox().inflate(2.0),
                    p -> p.getOwner() == this
            );
            if (!existing.isEmpty()) {
                existing.forEach(ConeOfColdProjectile::setDealDamageActive);
                return;
            }

            // 创建新的冰风锥形体
            ConeOfColdProjectile coneProjectile = new ConeOfColdProjectile(this.level(), this);
            coneProjectile.setPos(this.getX(), this.getEyeY() * 0.7 + this.getY(), this.getZ());
            coneProjectile.setDamage(3.0F * this.getQueenLevel()); // 按女王等级调整
            this.level().addFreshEntity(coneProjectile);
        }
    }

    private void spawnParticles() {
        // 雪花常驻粒子
        for (int i = 0; i < 3; ++i) {
            float px = (this.getRandom().nextFloat() - this.getRandom().nextFloat()) * 0.3F;
            float py = this.getEyeHeight() + (this.getRandom().nextFloat() - this.getRandom().nextFloat()) * 0.5F;
            float pz = (this.getRandom().nextFloat() - this.getRandom().nextFloat()) * 0.3F;
            this.level().addParticle(TFParticleType.SNOW_GUARDIAN.get(),
                    this.xOld + px, this.yOld + py, this.zOld + pz,
                    0.0, 0.0, 0.0);
        }


    }

    public boolean isBreathing() {
        return this.getEntityData().get(BEAM_FLAG);
    }

    public void setBreathing(boolean flag) {
        this.getEntityData().set(BEAM_FLAG, flag);
    }

    public void castSnowball(Entity target) {
        if (!(this.getSummoner() instanceof ServerPlayer)) return;
        Level level = this.level();

        Snowball orb = new Snowball(level, this); // 从女王位置发射
        orb.setOwner(this);
        orb.setPos(this.getX(), this.getEyeY() - orb.getBoundingBox().getYsize() * 0.5F, this.getZ());

        Vec3 direction = target.position().subtract(this.position()).normalize();
        orb.shoot(direction.x, direction.y + 0.1, direction.z, 1.2F, 0.5F);

        orb.setExplosionRadius(2.0F + this.getQueenLevel());
        orb.setDamage(40F);

        level.addFreshEntity(orb);
    }


    @Override
    public void onUnSummon() {
        if (!this.level().isClientSide) {
            MagicManager.spawnParticles(this.level(), ParticleTypes.POOF,
                    this.getX(), this.getY(), this.getZ(),
                    25, 0.4, 0.8, 0.4, 0.03, false);
            this.setRemoved(RemovalReason.DISCARDED);
        }
    }


    @Override
    public boolean shouldDespawnInPeaceful() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected boolean isSunBurnTick() {
        return false;
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        FlyingPathNavigation flyingpathnavigation = new FlyingPathNavigation(this, level);
        flyingpathnavigation.setCanOpenDoors(true);
        flyingpathnavigation.setCanFloat(true);
        flyingpathnavigation.setCanPassDoors(true);
        return flyingpathnavigation;
    }

    @Override
    public boolean wantsToAttack(LivingEntity target, LivingEntity owner) {
        if (target == owner) return false;
        return !(target instanceof SummonedSnowQueen);
    }


    private void castIceRay() {
        LivingEntity target = this.getTarget();
        if (target == null || !target.isAlive()) return;

        Level level = this.level();
        int queenLevel = this.getQueenLevel(); // 用 queenLevel 控制伤害/冻结时间

        // 范围和伤害参数（可根据 queenLevel 调整）
        float range = 30.0F;
        float damage = 3.0F + (queenLevel * 1.5F);
        int freezeTime = (int)(queenLevel * 10.0F); // tick


        HitResult hitResult = Utils.raycastForEntity(level, this, range, true, 0.15F);

        // 创建视觉效果（女王发射冰霜射线）
        level.addFreshEntity(new RayOfFrostVisualEntity(
                level,
                this.getEyePosition(),
                hitResult.getLocation(),
                this
        ));

        // 命中检测
        if (hitResult.getType() == HitResult.Type.ENTITY) {
            Entity hitEntity = ((EntityHitResult) hitResult).getEntity();

            // 取冰霜射线 Spell 实例（用注册表拿）
            AbstractSpell frostSpell = SpellRegistry.RAY_OF_FROST_SPELL.get();

            // 使用工厂方法创建 SpellDamageSource，并设置冻结时间
            SpellDamageSource frostSource = SpellDamageSource
                    .source(this, this, frostSpell)
                    .setFreezeTicks(freezeTime);

            // 造成伤害
            hitEntity.hurt(frostSource, damage);

            // 特效
            MagicManager.spawnParticles(level, ParticleHelper.ICY_FOG,
                    hitResult.getLocation().x, hitEntity.getY(), hitResult.getLocation().z,
                    4, 0, 0, 0, 0.3, true);
        }
        else if (hitResult.getType() == HitResult.Type.BLOCK) {
            MagicManager.spawnParticles(level, ParticleHelper.ICY_FOG,
                    hitResult.getLocation().x, hitResult.getLocation().y, hitResult.getLocation().z,
                    4, 0, 0, 0, 0.3, true);
        }

        // 雪花特效（不管击中什么都生成）
        MagicManager.spawnParticles(level, ParticleHelper.SNOWFLAKE,
                hitResult.getLocation().x, hitResult.getLocation().y, hitResult.getLocation().z,
                50, 0, 0, 0, 0.3, false);
    }


}

