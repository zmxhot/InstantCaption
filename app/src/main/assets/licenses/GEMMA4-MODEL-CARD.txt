---
license: apache-2.0
base_model:
- google/gemma-4-E2B-it
tags:
  - litert-lm
---

# litert-community/gemma-4-E2B-it-litert-lm

Main Model Card: [google/gemma-4-E2B-it](https://huggingface.co/google/gemma-4-E2B-it)

This model card provides the Gemma 4 E2B model in a way that is ready for deployment on Android, iOS, Desktop, IoT and Web.

Gemma is a family of lightweight, state-of-the-art open models from Google, built from the same research and technology used to create the Gemini models. This particular Gemma 4 model is small so it is ideal for on-device use cases. By running this model on device, users can have private access to Generative AI technology without even requiring an internet connection.

These models are provided in the `.litertlm` format for use with the LiteRT-LM framework. LiteRT-LM is a specialized orchestration layer built directly on top of LiteRT, Google’s high-performance multi-platform runtime trusted by millions of Android and edge developers. LiteRT provides the foundational hardware acceleration via XNNPack for CPU and ML Drift for GPU. LiteRT-LM adds the specialized GenAI libraries and APIs, such as KV-cache management, prompt templating, and function calling. This integrated stack is the same technology powering the Google AI Edge Gallery showcase app.

LiteRT-LM uses a state of the art [Gemma-4 mobile quantization scheme](https://blog.google/innovation-and-ai/technology/developers-tools/quantization-aware-training-gemma-4/) that uses a mixture of 2bit, 4bit and 8 bit weights. This means that for text only use cases the weight footprint in memory can be as low as 0.8 GB while the runtime uses memory mapping to support the 1.12GB of embedding parameters.  This approach gives significant working memory savings on some platforms as seen in the more detailed data below.  Additionally the Vision and Audio models are loaded on demand to further reduce memory consumption.  

## Try Gemma 4 E2B

<div align="center">

| [<svg xmlns="http://www.w3.org/2000/svg" height="72px" viewBox="0 -960 960 960" width="72px" fill="currentColor"><path d="M40-240q9-107 65.5-197T256-580l-74-128q-6-9-3-19t13-15q8-5 18-2t16 12l74 128q86-36 180-36t180 36l74-128q6-9 16-12t18 2q10 5 13 15t-3 19l-74 128q94 53 150.5 143T920-240H40Zm275.5-124.5Q330-379 330-400t-14.5-35.5Q301-450 280-450t-35.5 14.5Q230-421 230-400t14.5 35.5Q259-350 280-350t35.5-14.5Zm400 0Q730-379 730-400t-14.5-35.5Q701-450 680-450t-35.5 14.5Q630-421 630-400t14.5 35.5Q659-350 680-350t35.5-14.5Z"/></svg>](https://play.google.com/store/apps/details?id=com.google.ai.edge.gallery&pli=1) | [<svg xmlns="http://www.w3.org/2000/svg" height="84px" viewBox="0 -960 960 960" width="84px" fill="currentColor"><path d="M160-615v-60h60v60h-60Zm0 335v-275h60v275h-60Zm292 0H347q-24.75 0-42.37-17.63Q287-315.25 287-340v-280q0-24.75 17.63-42.38Q322.25-680 347-680h105q24.75 0 42.38 17.62Q512-644.75 512-620v280q0 24.75-17.62 42.37Q476.75-280 452-280Zm-105-60h105v-280H347v280Zm228 60v-60h165v-114H635q-24.75 0-42.37-17.63Q575-489.25 575-514v-106q0-24.75 17.63-42.38Q610.25-680 635-680h165v60H635v106h105q24.75 0 42.38 17.62Q800-478.75 800-454v114q0 24.75-17.62 42.37Q764.75-280 740-280H575Z"/></svg>](https://apps.apple.com/us/app/google-ai-edge-gallery/id6749645337) | [<svg xmlns="http://www.w3.org/2000/svg" height="72px" viewBox="0 -960 960 960" width="72px" fill="currentColor"><path d="M320-120v-40l80-80H160q-33 0-56.5-23.5T80-320v-440q0-33 23.5-56.5T160-840h640q33 0 56.5 23.5T880-760v440q0 33-23.5 56.5T800-240H560l80 80v40H320ZM160-440h640v-320H160v320Zm0 0v-320 320Z"/></svg>](https://ai.google.dev/edge/litert-lm/cli) | [<svg xmlns="http://www.w3.org/2000/svg" height="72px" viewBox="0 -960 960 960" width="72px" fill="currentColor"><path d="M160-120q-33 0-56.5-23.5T80-200v-560q0-33 23.5-56.5T160-840h560q33 0 56.5 23.5T800-760v80h80v80h-80v80h80v80h-80v80h80v80h-80v80q0 33-23.5 56.5T720-120H160Zm0-80h560v-560H160v560Zm80-80h200v-160H240v160Zm240-280h160v-120H480v120Zm-240 80h200v-200H240v200Zm240 200h160v-240H480v240ZM160-760v560-560Z"/></svg>](https://ai.google.dev/edge/litert-lm/cli) | [<svg xmlns="http://www.w3.org/2000/svg" height="72px" viewBox="0 -960 960 960" width="72px" fill="currentColor"><path d="M838-79 710-207v103h-60v-206h206v60H752l128 128-42 43Zm-358-1q-83 0-156-31.5T197-197q-54-54-85.5-126.36T80-478q0-83.49 31.5-156.93Q143-708.36 197-762.68 251-817 324-848.5 397-880 480-880t156 31.5q73 31.5 127 85.82 54 54.32 85.5 127.75Q880-561.49 880-478q0 23-2 44.5t-7 43.5h-63q6-21.67 9-43.33 3-21.67 3-44.47 0-22.8-2.95-45.6-2.94-22.8-8.83-45.6H648q2 23 4 45.5t2 45q0 22.5-1.25 44.5T649-390h-61q3-22 4.5-44t1.5-44q0-22.75-1.5-45.5T588-569H373.42q-3.42 23-4.92 45.5t-1.5 45q0 22.5 1.5 44.5t4.5 44h197v60H384q14 53 34 104t62 86q23 0 45-2.5t45-7.5v60q-23 5-45 7.5T480-80ZM151.78-390H312q-2.5-22-3.75-44T307-478q0-22.75 1-45.5t3-45.5H151.71q-5.85 22.8-8.78 45.6-2.93 22.8-2.93 45.6t2.95 44.47q2.94 21.66 8.83 43.33ZM172-629h149.59q11.41-48 28.91-93.5T395-810q-71 24-129.5 69.5T172-629Zm222 478q-26-41-43.5-86T323-330H172q33 67 91 114t131 65Zm-10-478h193q-13-54-36-104t-61-89q-38 40-61 89.5T384-629Zm255.34 0H788q-35-66-93-112t-129-68q27 41 44.5 86.5t28.84 93.5Z"/></svg>](https://huggingface.co/spaces/tylermullen/Gemma4) |
| :---: | :---: | :---: | :---: | :---: |
| [Android](https://play.google.com/store/apps/details?id=com.google.ai.edge.gallery&pli=1) | [iOS](https://apps.apple.com/us/app/google-ai-edge-gallery/id6749645337) | [Desktop](https://ai.google.dev/edge/litert-lm/cli) | [IoT](https://ai.google.dev/edge/litert-lm/cli) | [Web](https://huggingface.co/spaces/tylermullen/Gemma4) |

</div>


## Build with Gemma 4 E2B and LiteRT-LM

Ready to integrate this into your product? Get started [here](https://ai.google.dev/edge/litert-lm/overview).

## Gemma 4 E2B Performance on LiteRT-LM

All benchmarks were taken using 1024 prefill tokens and 256 decode tokens with a context length of 2048 tokens via LiteRT-LM. The model can support up to 32k context length. The inference on CPU is accelerated via the LiteRT XNNPACK delegate with 4 threads. Time-to-first-token does not include load time. Benchmarks were run with caches enabled and initialized. During the first run, the latency and memory usage may differ. Model size is the size of the file on disk.

CPU memory was measured using, `rusage::ru_maxrss` on Android, Linux and Raspberry Pi, `task_vm_info::phys_footprint` on iOS and MacBook and `process_memory_counters::PrivateUsage` on Windows.

**Android**

*Note: On [supported Android devices](https://developers.google.com/ml-kit), Gemma 4 is available through Android AI Core as [Gemini Nano](https://developer.android.com/ai/gemini-nano#architecture), which is the recommended path for production applications.*


| Device &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;| Backend | Prefill (tokens/sec) | Decode (tokens/sec) | <span style="white-space: nowrap;">Time-to-first</span>-token (sec) | Model size (MB) | CPU Memory (MB) |
| :---- | :---- | :---- | :---- | :---- | :---- | :---- |
| S26 Ultra | CPU | 557 | 46.9 | 1.8 | 2583 | 1733 |
| S26 Ultra | GPU | 3,808 | 52.1 | 0.3 | 2583 | 676 |

**🚨 NEW: Android with Speculative Decoding 🚨**

*The numbers in this section include speculative decoding. Speculative decoding is an optimization that accelerates LLMs by using a small, fast "draft" model to quickly predict multiple upcoming tokens, while a larger “target” model then verifies those tokens in parallel. The effectiveness of speculative decoding is task dependent because the “draft” model can more easily predict the correct tokens of some tasks. The metrics in this section were collected from a variety of sample prompts and grouped into categories by task type. The baseline measurements are an average across all task types. The number of input and output tokens varied across prompts. Note that if you download this model before May 5, 2026, you should re-download the model if you want to use speculative decoding. Speculative decoding is available on CPU and GPU on Mobile and Desktop.*

| Device &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;| Backend | Task Type | Speculative Decoding? | Decode (tokens/sec) | CPU Memory (MB) |
| :---- | :---- | :---- | :---- | :---- | :---- |
| S26 Ultra | CPU | Baseline | No | 40.7 | 1362 |
| S26 Ultra | CPU | Summarize text | Yes | 47.5 | 1582 |
| S26 Ultra | CPU | Code snippet | Yes | 36.3 | 1440 |
| S26 Ultra | CPU | Rewrite tone | Yes | 47.1 | 1408 |
| S26 Ultra | CPU | Free form | Yes | 38.1 | 1459 |
| S26 Ultra | GPU | Baseline | No | 51.5 | 791 |
| S26 Ultra | GPU | Summarize text | Yes | 91.7 | 817 |
| S26 Ultra | GPU | Code snippet | Yes | 84.4 | 788 |
| S26 Ultra | GPU | Rewrite tone | Yes | 87.4 | 762 |
| S26 Ultra | GPU | Free form | Yes | 66.5 | 804 |


**iOS**

| Device &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;| Backend | Prefill (tokens/sec) | Decode (tokens/sec) | <span style="white-space: nowrap;">Time-to-first</span>-token (sec) | Model size (MB) | CPU/GPU Memory (MB) |
| :---- | :---- | :---- | :---- | :---- | :---- | :---- |
| iPhone 17 Pro | CPU | 532 | 25.0 | 1.9 | 2583 | 607 |
| iPhone 17 Pro | GPU | 2,878 | 56.5 | 0.3 | 2583 | 1450 |

**Linux**

| Device &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;| Backend | Prefill (tokens/sec) | Decode (tokens/sec) | <span style="white-space: nowrap;">Time-to-first</span>-token (sec) | Model size (MB) | CPU Memory (MB) |
| :---- | :---- | :---- | :---- | :---- | :---- | :---- |
| Arm 2.3 & 2.8GHz | CPU | 260 | 35.0 | 4.0 | 2583 | 1628 | 
| NVIDIA GeForce RTX 4090 | GPU | 11,234 | 143.4 | 0.1 | 2583 | 913 | 

**macOS**

| Device &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;| Backend | Prefill (tokens/sec) | Decode (tokens/sec) | <span style="white-space: nowrap;">Time-to-first</span>-token (sec) | Model size (MB) | CPU/GPU Memory (MB) |
| :---- | :---- | :---- | :---- | :---- | :---- | :---- |
| MacBook Pro M4 Max | CPU | 901 | 41.6 | 1.1 | 2583 | 736 |
| MacBook Pro M4 Max | GPU | 7,835 | 160.2 | 0.1 | 2583 | 1623 |

**Windows**

| Device &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;| Backend | Prefill (tokens/sec) | Decode (tokens/sec) | <span style="white-space: nowrap;">Time-to-first</span>-token (sec) | Model size (MB) | CPU Memory (MB) |
| :---- | :---- | :---- | :---- | :---- | :---- | :---- | 
| Intel LunarLake | CPU | 435 | 29.8 | 2.39 | 2583 | 3505 |
| Intel LunarLake | GPU | 3,751 | 48.4 | 0.29 | 2583 | 3540 |

**Web**

| Device &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;| Backend | Prefill (tokens/sec) | Decode (tokens/sec) | <span style="white-space: nowrap;">Time-to-first</span>-token (sec) | Model size (MB) | GPU Memory (MB) |
| :---- | :---- | :---- | :---- | :---- | :---- | :---- |
| Macbook Pro M4 Max | WebGPU | 4,853 | 73 | 1.09 | 2008 | ~1800 |

<small>

  * Web on LiteRT-LM uses a [specially optimized model](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/main/gemma-4-E2B-it-web.litertlm) for Web because of its unique memory constraints. Currently the model is text-only.

</small>


**IoT**

| Device &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;| Backend | Prefill (tokens/sec) | Decode (tokens/sec) | <span style="white-space: nowrap;">Time-to-first</span>-token (sec) | Model size (MB) | CPU Memory (MB) |
| :---- | :---- | :---- | :---- | :---- | :---- | :---- |
| Raspberry Pi 5 16GB | CPU | 133 | 7.6 | 7.8 | 2583 | 1546 |
| Jetson Orin Nano | CPU | 109 | 12.2 | 9.4 | 2583 | 3681 |
| Jetson Orin Nano | GPU | 1,142 | 24.2 | 0.9 | 2583 | 2739 | 
| Qualcomm Dragonwing IQ8 (IQ-8275) | NPU | 3,747 | 31.7 | 0.3 | 2967 | 1869 |

<small>

  * NPU model is benchmarked with 4096 context length

</small>


## Running Gemma 4 E2B on Web with MediaPipe
You can also run Gemma through MediaPipe [LLM Inference Engine](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/web_js). **However, this route is currently in maintenance mode.** To add it to your existing MediaPipe flow, download the [*gemma-4-E2B-it-web.task*](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/main/gemma-4-E2B-it-web.task) model file and run with our [sample web page](https://github.com/google-ai-edge/mediapipe-samples/blob/main/examples/llm_inference/js/README.md), or follow the [guide](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/web_js) to add it to your own app.

