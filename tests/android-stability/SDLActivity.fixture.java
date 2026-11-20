// Lines of SDL 2.32.10 SDLActivity.java that scripts/android-patch-sdl-java.sh rewrites.
class SDLActivity {
    public void setOrientationBis(int w, int h, boolean resizable, String hint)
    {
        int orientation_landscape = -1;
        int orientation_portrait = -1;

        /* If set, hint "explicitly controls which UI orientations are allowed". */
        if (hint.contains("LandscapeRight") && hint.contains("LandscapeLeft")) {
            orientation_landscape = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        } else if (hint.contains("LandscapeLeft")) {
            orientation_landscape = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        } else if (hint.contains("LandscapeRight")) {
            orientation_landscape = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        }

        /* exact match to 'Portrait' to distinguish with PortraitUpsideDown */
        boolean contains_Portrait = hint.contains("Portrait ") || hint.endsWith("Portrait");

        if (contains_Portrait && hint.contains("PortraitUpsideDown")) {
            orientation_portrait = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
        } else if (contains_Portrait) {
            orientation_portrait = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        } else if (hint.contains("PortraitUpsideDown")) {
            orientation_portrait = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
        }

        boolean is_landscape_allowed = (orientation_landscape != -1);
        boolean is_portrait_allowed = (orientation_portrait != -1);
        int req; /* Requested orientation */

        /* No valid hint, nothing is explicitly allowed */
        if (!is_portrait_allowed && !is_landscape_allowed) {
            if (resizable) {
                /* All orientations are allowed, respecting user orientation lock setting */
                req = ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
            } else {
                /* Fixed window and nothing specified. Get orientation from w/h of created window */
                req = (w > h ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            }
        } else {
            /* At least one orientation is allowed */
            if (resizable) {
                if (is_portrait_allowed && is_landscape_allowed) {
                    /* hint allows both landscape and portrait, promote to full user */
                    req = ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
                } else {
                    /* Use the only one allowed "orientation" */
                    req = (is_landscape_allowed ? orientation_landscape : orientation_portrait);
                }
            } else {
                /* Fixed window and both orientations are allowed. Choose one. */
                if (is_portrait_allowed && is_landscape_allowed) {
                    req = (w > h ? orientation_landscape : orientation_portrait);
                } else {
                    /* Use the only one allowed "orientation" */
                    req = (is_landscape_allowed ? orientation_landscape : orientation_portrait);
                }
            }
        }

        Log.v(TAG, "setOrientation() requestedOrientation=" + req + " width=" + w +" height="+ h +" resizable=" + resizable + " hint=" + hint);
        mSingleton.setRequestedOrientation(req);
    }
}

/* DummyEdit's input connection from SDL 2.32.10, for the physical-keyboard patch */
class DummyEdit extends View {
    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        ic = new SDLInputConnection(this, true);

        outAttrs.inputType = InputType.TYPE_CLASS_TEXT |
                             InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI |
                              EditorInfo.IME_FLAG_NO_FULLSCREEN /* API 11 */;

        return ic;
    }
}
