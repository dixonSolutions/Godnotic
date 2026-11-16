// SPDX-License-Identifier: GPL-3.0-or-later
//! Inspect fresh automatic download plans: cargo run -p godnotic-loader-core
//! --features http-ureq --example dataplan
use godnotic_core::fetch::{datasets, http_ureq::UreqHttp, CancelToken};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let http = UreqHttp::new();
    let token = CancelToken::new();
    // An empty directory ensures a previous install cannot supply a stored manifest.
    let dir = std::env::temp_dir().join(format!("godnotic-dataplan-{}", std::process::id()));
    std::fs::create_dir(&dir)?;
    let result = (|| -> Result<(), Box<dyn std::error::Error>> {
        for astc in [true, false] {
            let choice = datasets::choose(None, None, Some(astc), None);
            println!("ASTC={astc}: {:?}, {}", choice.kind, choice.url);
            if let Some(m) = datasets::split_manifest(&http, &choice, Some(astc), &dir, &token)? {
                for p in &m.packs {
                    println!("  {} {}{}", p.name, p.size, if m.first_play.contains(&p.name) { " first_play" } else { "" });
                }
                println!("  first_play: {} packs, {} bytes; all: {} packs, {} bytes", m.first_play.len(), m.first_play_size(), m.packs.len(), m.total());
                std::fs::remove_file(dir.join(godnotic_core::fetch::manifest::STORED))?;
            } else {
                let (selected, plan) = datasets::plan_choice(&http, &choice, &token, datasets::FULL_URL)?;
                println!("  no split manifest; archive choice: {:?}", selected.kind);
                for e in &plan.entries { println!("  {} {} bytes (zip method {})", e.file_name, e.csize, e.method); }
                println!("  all: {} packs, {} bytes", plan.entries.len(), plan.entries.iter().map(|e| e.csize).sum::<u64>());
            }
        }
        Ok(())
    })();
    std::fs::remove_dir_all(dir)?;
    result
}
