package dev.flint.invsync;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
class FreezeGuardTest {
    @Test void blocksClicksDropsCommandsAndMovementWhileLoadingOrFenced(){
        InventorySync plugin=mock(InventorySync.class);Player player=mock(Player.class);when(plugin.blocked(player)).thenReturn(true);FreezeGuard guard=new FreezeGuard(plugin);
        InventoryClickEvent click=mock(InventoryClickEvent.class);when(click.getWhoClicked()).thenReturn(player);guard.click(click);verify(click).setCancelled(true);
        PlayerDropItemEvent drop=mock(PlayerDropItemEvent.class);when(drop.getPlayer()).thenReturn(player);guard.drop(drop);verify(drop).setCancelled(true);
        PlayerCommandPreprocessEvent command=mock(PlayerCommandPreprocessEvent.class);when(command.getPlayer()).thenReturn(player);guard.command(command);verify(command).setCancelled(true);verify(plugin).loading(player);
        PlayerMoveEvent move=mock(PlayerMoveEvent.class);when(move.getPlayer()).thenReturn(player);when(move.hasChangedPosition()).thenReturn(true);guard.move(move);verify(move).setCancelled(true);
    }
    @Test void readyPlayersAndLookingAroundAreNotBlocked(){InventorySync plugin=mock(InventorySync.class);Player p=mock(Player.class);FreezeGuard guard=new FreezeGuard(plugin);PlayerDropItemEvent drop=mock(PlayerDropItemEvent.class);when(drop.getPlayer()).thenReturn(p);guard.drop(drop);verify(drop,never()).setCancelled(anyBoolean());}
}
