/* Included by cd_shared.c, so the join can start the map's named cdtrack
 * without queueing a command behind a blocking world load. */
void TouchMusic_PlayTrack(const char *track)
{
	if (music_playlist_index.integer < 0)
		CDAudio_Play_byName(track, true, true, 0);
}
