package io.github.dixonsolutions.godnotic.fe;

import java.util.HashMap;
import java.util.Map;

/** Keeps both halves of Back/Escape with the recipient of the initial down. */
public final class EscapeKeyRoute {
    public static final int ENGINE = 0, OVERLAY = 1, BACK = 2;
    private final Map<Long, Boolean> owners = new HashMap<>();

    public int event(int device, int key, boolean down, boolean visible, boolean canceled) {
        long id = ((long) device << 32) | (key & 0xffffffffL);
        if (down) {
            Boolean owner = owners.get(id);
            if (owner == null) {
                owner = visible;
                owners.put(id, owner);
            }
            return owner ? OVERLAY : ENGINE;
        }
        Boolean owner = owners.remove(id);
        if (owner == null) return visible ? OVERLAY : ENGINE;
        if (!owner) return ENGINE;
        return visible && !canceled ? BACK : OVERLAY;
    }
}
