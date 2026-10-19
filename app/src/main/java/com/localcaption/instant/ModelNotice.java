package com.localcaption.instant;

import android.content.Context;

/** Model attribution belongs beside the downloaded model, independently of the app license. */
final class ModelNotice {
    static String details(Context c,String id){
        String author,license,url;
        QwenModel q=QwenModel.fromId(id);String target=TranslationModel.targetFromId(id);
        if(q==QwenModel.HYMT2){author="Tencent Hy / llama.cpp";license="Apache-2.0 / MIT runtime";url="https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF";}
        else if(q==QwenModel.GEMMA3){author="Google Gemma / LiteRT Community";license="Gemma Terms of Use · publisher authorization required";url="https://huggingface.co/litert-community/Gemma3-1B-IT";}
        else if(q==QwenModel.GEMMA4){author="Google Gemma / LiteRT Community";license="Apache-2.0";url="https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm";}
        else if(q!=null){author="Alibaba Qwen / Qualcomm AI Hub";license="Apache-2.0 + Qualcomm model usage limitations";url="https://huggingface.co/qualcomm/"+q.label;}
        else if(target!=null){author="University of Helsinki / OPUS-MT";license=target.equals("de")?"CC BY 4.0":"Apache-2.0";url="https://huggingface.co/Helsinki-NLP/opus-mt-en-"+(target.equals("pt")?"ROMANCE":target);}
        else{NpuModel model=NpuModel.fromId(id);
            if(model==NpuModel.SENSEVOICE||model==NpuModel.SENSEVOICE_CPU){author="FunAudioLLM / Alibaba";license="FunASR Model Open Source License";url="https://huggingface.co/FunAudioLLM/SenseVoiceSmall";}
            else if(model==NpuModel.NEMOTRON){author="NVIDIA";license="NVIDIA Open Model License";url="https://huggingface.co/nvidia/nemotron-speech-streaming-en-0.6b";}
            else if(model.isWhisper()){author="OpenAI Whisper";license="MIT";url="https://github.com/openai/whisper";}
            else if(model==NpuModel.X_ASR){author="Gilgamesh-J / X-ASR";license="Apache-2.0";url="https://github.com/Gilgamesh-J/X-ASR";}
            else{author="k2-fsa / icefall";license="Apache-2.0";url="https://github.com/k2-fsa/icefall";}
        }
        return author+"\n"+license+"\n"+url;
    }
}
