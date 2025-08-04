package com.rinko1231.SnowWaifuSpell.ai;

import com.rinko1231.SnowWaifuSpell.entity.SummonedSnowQueen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import twilightforest.entity.ai.goal.HoverBaseGoal;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class NewHoverBeamGoal extends HoverBaseGoal<SummonedSnowQueen> {
    private int hoverTimer;
    private final int maxHoverTime;

    public NewHoverBeamGoal(SummonedSnowQueen snowQueen, int hoverTime) {
        super(snowQueen, 6.0F, hoverTime);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        this.maxHoverTime = hoverTime;
        this.hoverTimer = 0;
    }

    @Override
    public boolean canUse() {
        LivingEntity target = this.attacker.getTarget();
        return target != null && target.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = this.attacker.getTarget();
        return target != null && target.isAlive();
    }

    @Override
    public void stop() {
        this.hoverTimer = 0;
        this.attacker.setBreathing(false);
        this.attacker.getNavigation().stop();
    }

    @Override
    public void tick() {
        LivingEntity target = this.attacker.getTarget();
        if (target == null) {
            this.attacker.getNavigation().stop();
            return;
        }

        // 每次 tick 都更新悬停位置
        updateHoverPosition(target);

        // 朝向目标
        this.attacker.lookAt(target, 30.0F, 30.0F);
        this.attacker.getLookControl().setLookAt(target);

        // 悬停计时
        ++this.hoverTimer;
        if (this.hoverTimer >= this.maxHoverTime) {
            this.attacker.setBreathing(true);
            this.doRayAttack();
            this.hoverTimer = 0; // 循环释放技能
        }

        // 移动到悬停点
        double distanceSq = this.attacker.distanceToSqr(this.hoverPosX, this.hoverPosY, this.hoverPosZ);
        if (distanceSq > 4.0) {
            this.attacker.getNavigation().moveTo(this.hoverPosX, this.hoverPosY, this.hoverPosZ, 1.8); // ✅ 加快移动速度
        } else {
            this.attacker.getNavigation().stop();
        }
    }

    private void updateHoverPosition(LivingEntity target) {
        final double horizontalOffset = 8.0 + this.attacker.getRandom().nextDouble() * 4.0;
        final double verticalOffset = 5.0 + this.attacker.getRandom().nextDouble() * 2.0;

        Vec3 toTarget = target.position().subtract(this.attacker.position()).normalize();
        Vec3 hoverOffset = new Vec3(-toTarget.z, 0, toTarget.x).scale(horizontalOffset);

        this.hoverPosX = target.getX() + hoverOffset.x;

        // 如果目标是主人且主人在地面，降低悬停高度
        double baseY = target.getY();
        if (target == this.attacker.getOwner() && target.onGround()) {
            this.hoverPosY = baseY + 2.0;
        } else {
            double desiredHoverY = baseY + verticalOffset;
            double maxHoverY = baseY + 4.0;
            this.hoverPosY = Math.min(desiredHoverY, maxHoverY);
        }

        this.hoverPosZ = target.getZ() + hoverOffset.z;
    }
    private void doRayAttack() {
        double range = 5.0D;
        Vec3 srcVec = new Vec3(this.attacker.getX(), this.attacker.getY() + 1.7, this.attacker.getZ());
        Vec3 lookVec = this.attacker.getViewVector(1.0F);
        Vec3 destVec = srcVec.add(lookVec.x() * range, lookVec.y() * range, lookVec.z() * range);

        List<Entity> possibleList = this.attacker.level().getEntities(
                this.attacker,
                this.attacker.getBoundingBox().inflate(range)
        );

        for (Entity possibleEntity : possibleList) {
            if (possibleEntity.isPickable() && possibleEntity != this.attacker && possibleEntity != this.attacker.getOwner()) {
                float borderSize = possibleEntity.getPickRadius();
                AABB collisionBB = possibleEntity.getBoundingBox().inflate(borderSize);
                Optional<Vec3> interceptPos = collisionBB.clip(srcVec, destVec);

                if (collisionBB.contains(srcVec) || interceptPos.isPresent()) {
                    this.attacker.doBreathAttack();
                }
            }
        }
    }
}
