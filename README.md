# Skarmetoo

v1.02

**Skarmetoo** is an intelligent mobile gallery assistant that automatically analyzes, tags, and adds context to your screenshots, making them instantly searchable.

It acts as a privacy-first, on-device alternative to Pixel Screenshots, utilizing local AI models to unlock powerful context awareness for your visual data without ever sending your images to the cloud.

## Key Features
*   **On-Device Analysis:** Runs powerful Gemma models locally on your hardware for maximum privacy.
*   **Context & Tag Search:** Find screenshots more easily with context search.
*   **Private & Secure:** Your images and analysis results never leave your phone.
*   **Note Taking:** Add custom context or reminders to any screenshot entry.
*   **High-Quality Sharing:** Generate image card with dynamic color-theming based on the image palette.
*   **Multilingual Support:** Supports screenshot analysis and summaries in 7 languages (English, Traditional Chinese, Hindi, Spanish, Arabic, French, and Russian).
*   **Smart Organization:** Automatically extracts tags and summaries for a clutter-free gallery experience.

## Why On-Device AI?
Skarmetoo utilizes local models like [Gemma 3n](https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/tree/main) and [Gemma 4](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) (via Google LiteRT) because:
1.  **Total Privacy:** No cloud APIs mean your personal visual data is never uploaded, scanned, or stored on remote servers.
2.  **No Costs or Keys:** You don't need to manage API keys or worry about daily quotas or monthly billing. It is your hardware doing the work.
3.  **No Restrictions:** Unlike cloud AI services bound by corporate content policies and server-side filters, the model runs entirely under your control, no content moderation, no request blocking, no one looking over your shoulder. What you do with it is your business.

## Device Requirements

While Skarmetoo runs locally, it requires a device with a modern processor and sufficient memory to handle the AI models.

Device I've tried:
| Name | SoC | RAM |
| :--- | :--- | :--- |
| Poco X8 Pro Max | Dimensity 9500s | 12GB |
| Galaxy S23 | Snapdragon 8 Gen 2 | 8GB |
| OnePlus 7T | Snapdragon 855+ | 8GB |
| OnePlus Nord CE 3 Lite | Snapdragon 695 5G | 8GB |
| Galaxy A17 4G | Helio G99 | 8GB |
| Galaxy Tab A9 | Helio G99 | 4GB |

Note: 8GB of RAM is highly recommended for a smooth analysis experience. Devices with 4GB RAM may experience slower processing or occasional memory limitations during intensive analysis tasks.

**Important Usage Notes:**
- **App Switching:** Do not switch to other apps while analysis is in progress, as the process may stop.
- **Battery & Heat:** Analyzing images locally is resource-intensive. It WILL consume battery and may cause your device to heat up.
- **Screen Saver:** Use the built-in screen saver feature to save battery during long analysis sessions.
- **Disclaimer:** Use the application at your own risk, if facing frequent app crashes, it might not compatible with your device.

## Security & Privacy
**Is my data secure?**
Yes. Your screenshots remain in your storage, and the analysis database is stored locally on your device. While the data is stored in standard local database format, the fundamental advantage is the **complete absence of internet transmission** for your visual information.

## Guide
1. Open the app and go to the **Settings** page.
2. Select one of the available models (**Gemma 4 is highly recommended** for best performance and ease of use).
3. **Download Process:** 
   - For **Gemma 4**: Simply close the login window (tap the **X**) if it appears, and the download will start immediately.
   - For **Gemma 3n**: You must log in to your Hugging Face account and ensure you have authorized the model license to begin the download.
4. Stay on the app while the download completes. Do **not** quit the app or lock your phone, as this will suspend the download. You can use the built-in **Screen Saver** feature to safely leave the phone running.
5. Once the download is complete, the model will initialize and load itself automatically. A green indicator will appear when it is ready.
6. Add your screenshot folders to the queue on the main page. The model will automatically start analyzing and tagging your images.

## Contributing
Contributions are welcome! Please feel free to submit a Pull Request.

<a href="https://buymeacoffee.com/derykcihc" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" style="height: 60px !important;width: 217px !important;" ></a>

If you appreciate the project and want to help keep the app evolving, you can donate via Buy Me a Coffee. Otherwise, it is always free on GitHub!