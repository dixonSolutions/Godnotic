// SPDX-License-Identifier: GPL-3.0-or-later
//! Pictures by image ref (docs/UI_BRIDGE.md 5.2). A picture asks once for its
//! ref at the box it draws, shows a pulsing placeholder until the engine
//! answers, and drops its texture when its row or card is unbound. Textures
//! are cached per ref for the session (bounded).

use gtk::prelude::*;
use gtk::{gdk, glib};
use std::collections::{HashMap, VecDeque};

#[derive(Default)]
pub struct Images {
    textures: HashMap<String, gdk::Texture>,
    order: VecDeque<String>,
    texture_bytes: usize,
    retries: HashMap<String, u8>,
    waiting: HashMap<String, Vec<glib::WeakRef<gtk::Picture>>>,
}

const MAX_TEXTURES: usize = 300;
// Visible pictures keep their own references after cache eviction.
const MAX_TEXTURE_BYTES: usize = 128 * 1024 * 1024;

fn texture_bytes(t: &gdk::Texture) -> usize {
    (t.width() as usize).saturating_mul(t.height() as usize).saturating_mul(4)
}

impl Images {
    /// How many pictures whose ref starts with `prefix` have arrived (script `measure`).
    pub fn arrived_count(&self, prefix: &str) -> usize {
        self.textures.keys().filter(|r| r.starts_with(prefix)).count()
    }

    /// Shows the cached texture or registers the picture to receive it; true when the engine must be asked.
    pub fn attach(&mut self, r: &str, pic: &gtk::Picture) -> bool {
        // Grid rows are recycled. A late reply for the old row must not paint
        // that image over the new row's picture.
        for pictures in self.waiting.values_mut() {
            pictures.retain(|weak| weak.upgrade().is_some_and(|old| old != *pic));
        }
        self.waiting.retain(|_, pictures| !pictures.is_empty());
        if let Some(t) = self.textures.get(r) {
            pic.set_paintable(Some(t));
            pic.remove_css_class("gn-placeholder");
            pic.remove_css_class("gn-missing");
            return false;
        }
        pic.set_paintable(gdk::Paintable::NONE);
        pic.remove_css_class("gn-missing");
        pic.add_css_class("gn-placeholder");
        let list = self.waiting.entry(r.to_string()).or_default();
        let first = list.is_empty();
        list.push(pic.downgrade());
        first
    }

    fn put(&mut self, r: &str, t: gdk::Texture) {
        if let Some(old) = self.textures.remove(r) {
            self.texture_bytes = self.texture_bytes.saturating_sub(texture_bytes(&old));
            self.order.retain(|key| key != r);
        }
        self.texture_bytes = self.texture_bytes.saturating_add(texture_bytes(&t));
        self.order.push_back(r.to_string());
        self.textures.insert(r.to_string(), t.clone());
        self.retries.remove(r);
        while self.order.len() > 1 && (self.order.len() > MAX_TEXTURES || self.texture_bytes > MAX_TEXTURE_BYTES) {
            if let Some(old) = self.order.pop_front() {
                if let Some(texture) = self.textures.remove(&old) {
                    self.texture_bytes = self.texture_bytes.saturating_sub(texture_bytes(&texture));
                }
            }
        }
        for w in self.waiting.remove(r).unwrap_or_default() {
            if let Some(p) = w.upgrade() {
                p.set_paintable(Some(&t));
                p.remove_css_class("gn-placeholder");
                p.remove_css_class("gn-missing");
            }
        }
    }

    pub fn arrived(&mut self, r: &str, path: Option<&str>, data: Option<&[u8]>) -> Option<u64> {
        let t = match (data, path) {
            (Some(d), _) => gdk::Texture::from_bytes(&glib::Bytes::from(d)).ok(),
            (None, Some(p)) => gdk::Texture::from_filename(p).ok(),
            _ => None,
        };
        match t {
            Some(t) => { self.put(r, t); None },
            None => self.missing(r),
        }
    }

    /// Keep live waiters so a map installed after this reply can fill the card.
    /// The caller retries with backoff while a picture remains visible.
    pub fn missing(&mut self, r: &str) -> Option<u64> {
        let waiters = self.waiting.get_mut(r)?;
        waiters.retain(|w| {
            if let Some(p) = w.upgrade() {
                p.remove_css_class("gn-placeholder");
                p.add_css_class("gn-missing");
                true
            } else { false }
        });
        let attempts = self.retries.entry(r.to_string()).or_default();
        if !r.starts_with("pk3:maps/") || waiters.is_empty() { return None; }
        let delay = (2u64 << (*attempts).min(5)).min(60);
        *attempts = attempts.saturating_add(1);
        Some(delay)
    }

    pub fn has_visible_waiter(&self, r: &str) -> bool {
        self.waiting.get(r).is_some_and(|pictures| pictures.iter().any(|w| w.upgrade().is_some_and(|p| p.is_mapped())))
    }

    /// The loader's own brand assets, available before any engine runs.
    pub fn preload_brand(&mut self) {
        let banner = glib::Bytes::from_static(include_bytes!("../data/banner-800.png"));
        if let Ok(t) = gdk::Texture::from_bytes(&banner) {
            self.put("asset:banner", t);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recycled_picture_ignores_the_previous_rows_late_reply() {
        gtk::init().expect("GTK display required");
        let texture = gdk::Texture::from_bytes(&glib::Bytes::from_static(include_bytes!("../data/banner-800.png"))).unwrap();
        let picture = gtk::Picture::new();
        let mut images = Images::default();
        assert!(images.attach("old", &picture));
        assert!(images.attach("new", &picture));
        images.put("old", texture.clone());
        assert!(picture.paintable().is_none(), "a stale response painted the recycled card");
        images.put("new", texture.clone());
        assert!(picture.paintable().is_some());
        let retry = gtk::Picture::new();
        assert!(images.attach("missing", &retry));
        images.missing("missing");
        assert!(retry.has_css_class("gn-missing"));
        images.put("missing", texture.clone());
        assert!(!retry.has_css_class("gn-missing"));
        let downloading = gtk::Picture::new();
        assert!(images.attach("pk3:maps/downloading", &downloading));
        for delay in [2, 4, 8, 16, 32, 60, 60] {
            assert_eq!(images.missing("pk3:maps/downloading"), Some(delay));
        }
        images.put("pk3:maps/downloading", texture.clone());
        assert!(downloading.paintable().is_some());
        assert!(!downloading.has_css_class("gn-missing"));
        for n in 0..300 {
            images.put(&format!("preview-{n}"), texture.clone());
        }
        assert!(images.texture_bytes <= MAX_TEXTURE_BYTES);
        assert!(images.order.len() < 300);
    }
}
