package me.zircta.accuratecps;

import java.util.ArrayList;

/**
 * Counter state that is injected into net.minecraft.client.Minecraft itself.
 *
 * This class is never loaded directly: at transform time its members are copied
 * into the Minecraft ClassNode (with the owner remapped). That is required
 * because Lunar's GenesisClassLoader cannot see classes from the agent jar,
 * so the tracker cannot call back into this class.
 */
public final class CpsRuntime {

    public static ArrayList<Object> fastcps$leftList;
    public static ArrayList<Object> fastcps$rightList;

    private CpsRuntime() {
    }

    public static ArrayList<Object> fastcps$list(boolean right) {
        ArrayList<Object> list = right ? fastcps$rightList : fastcps$leftList;
        if (list == null) {
            list = new ArrayList<Object>();
            if (right) {
                fastcps$rightList = list;
            } else {
                fastcps$leftList = list;
            }
        }
        return list;
    }

    public static void fastcps$leftClick() {
        fastcps$list(false).add(Long.valueOf(System.currentTimeMillis()));
    }

    public static void fastcps$rightClick() {
        fastcps$list(true).add(Long.valueOf(System.currentTimeMillis()));
    }

    public static void fastcps$tick() {
        fastcps$prune(fastcps$list(false));
        fastcps$prune(fastcps$list(true));
    }

    public static int fastcps$left() {
        return fastcps$count(fastcps$list(false));
    }

    public static int fastcps$right() {
        return fastcps$count(fastcps$list(true));
    }

    private static void fastcps$prune(ArrayList<Object> list) {
        long now = System.currentTimeMillis();
        int i = 0;
        while (i < list.size()) {
            Object value = list.get(i);
            if (value instanceof Long && now - ((Long) value).longValue() > 1000L) {
                list.remove(i);
            } else {
                i++;
            }
        }
    }

    private static int fastcps$count(ArrayList<Object> list) {
        long now = System.currentTimeMillis();
        int count = 0;
        for (int i = 0; i < list.size(); i++) {
            Object value = list.get(i);
            if (value instanceof Long && now - ((Long) value).longValue() <= 1000L) {
                count++;
            }
        }
        return count;
    }
}
