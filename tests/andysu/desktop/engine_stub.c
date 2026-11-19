/* Desktop engine library (libmain.so for the swap harness): what the Android build gets from
 * vid_touchdetect.c's JNI section, which is Android-only. */
void Andysu_InputDevices(int touch, int keyboard, int mouse)
{
	(void)touch; (void)keyboard; (void)mouse;
}
