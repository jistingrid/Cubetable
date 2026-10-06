package dev.tacticalcombat.mixin.client;

import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Lets the dice overlay see how many chat lines are currently showing, so it can sit just above them. */
@Mixin(ChatHud.class)
public interface ChatHudAccessor {
	/** Newest line first. */
	@Accessor("visibleMessages")
	List<ChatHudLine.Visible> tacticalcombat$getVisibleMessages();
}
