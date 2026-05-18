// Tauri entry point: prevents extra console on Windows release builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    dewarp_desktop_lib::run();
}
