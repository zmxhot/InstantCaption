#include <jni.h>
#include <llama.h>
#include <algorithm>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

// Each Java translator owns its context. The Java side serializes calls and polls
// cancellation between bounded prefill batches and individual generated tokens.
namespace {
struct Session {
    llama_model *model=nullptr;
    llama_context *context=nullptr;
    llama_sampler *sampler=nullptr;
    std::vector<llama_token> prompt;
    size_t position=0;
    ~Session(){if(sampler)llama_sampler_free(sampler);if(context)llama_free(context);if(model)llama_model_free(model);}
};
void fail(JNIEnv *env,const char *message){env->ThrowNew(env->FindClass("java/io/IOException"),message);}
Session *session(jlong value){if(!value)throw std::runtime_error("CPU translation model is unloaded");return reinterpret_cast<Session*>(value);}
std::string bytes(JNIEnv *env,jbyteArray array){jsize n=env->GetArrayLength(array);std::string out(n,'\0');env->GetByteArrayRegion(array,0,n,reinterpret_cast<jbyte*>(out.data()));return out;}
thread_local std::string load_error;
thread_local bool loading=false;
void quiet(ggml_log_level level,const char *message,void*){
    // Collect model-loader errors only, without logging audio, prompts or tokens.
    if(loading&&level==GGML_LOG_LEVEL_ERROR&&message)load_error=std::string(message).substr(0,2048);
}
}

extern "C" JNIEXPORT jlong JNICALL Java_com_localcaption_instant_CpuTranslator_nativeLoad(JNIEnv *env,jclass,jbyteArray file,jint threads){
    try{
        static std::once_flag init;std::call_once(init,[]{llama_log_set(quiet,nullptr);llama_backend_init();});
        auto s=std::make_unique<Session>();std::string path=bytes(env,file);
        auto mp=llama_model_default_params();mp.n_gpu_layers=0;
        load_error.clear();loading=true;s->model=llama_model_load_from_file(path.c_str(),mp);loading=false;
        if(!s->model)throw std::runtime_error("Cannot load CPU translation model: "+load_error);
        auto cp=llama_context_default_params();cp.n_ctx=2048;cp.n_batch=64;cp.n_ubatch=64;cp.n_threads=std::clamp<int>(threads,1,6);cp.n_threads_batch=cp.n_threads;cp.no_perf=true;
        s->context=llama_init_from_model(s->model,cp);if(!s->context)throw std::runtime_error("Cannot allocate CPU translation context");
        s->sampler=llama_sampler_init_greedy();return reinterpret_cast<jlong>(s.release());
    }catch(const std::bad_alloc&){env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),"CPU translation memory allocation failed");}
    catch(const std::exception &e){fail(env,e.what());}return 0;
}

extern "C" JNIEXPORT void JNICALL Java_com_localcaption_instant_CpuTranslator_nativeBegin(JNIEnv *env,jclass,jlong handle,jbyteArray input){
    try{
        auto s=session(handle);std::string user=bytes(env,input);llama_chat_message message={"user",user.c_str()};
        const char *tmpl=llama_model_chat_template(s->model,nullptr);
        int count=llama_chat_apply_template(tmpl,&message,1,true,nullptr,0);
        if(count<=0||count>131072)throw std::runtime_error("Unsupported translation chat template");
        std::string formatted(count,'\0');int actual=llama_chat_apply_template(tmpl,&message,1,true,formatted.data(),count);
        if(actual!=count)throw std::runtime_error("Invalid translation chat template");
        auto vocab=llama_model_get_vocab(s->model);int n=-llama_tokenize(vocab,formatted.data(),formatted.size(),nullptr,0,true,true);
        if(n<=0||n>1792)throw std::runtime_error("CPU translation context limit");
        s->prompt.resize(n);if(llama_tokenize(vocab,formatted.data(),formatted.size(),s->prompt.data(),n,true,true)!=n)throw std::runtime_error("CPU tokenization failed");
        llama_memory_clear(llama_get_memory(s->context),true);llama_sampler_reset(s->sampler);s->position=0;
    }catch(const std::bad_alloc&){env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),"CPU tokenization allocation failed");}
    catch(const std::exception &e){fail(env,e.what());}
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_localcaption_instant_CpuTranslator_nativePrefill(JNIEnv *env,jclass,jlong handle){
    try{
        auto s=session(handle);if(s->position>=s->prompt.size())return JNI_TRUE;
        int n=std::min<size_t>(64,s->prompt.size()-s->position);
        auto batch=llama_batch_get_one(s->prompt.data()+s->position,n);
        if(llama_decode(s->context,batch)!=0)throw std::runtime_error("CPU translation prefill failed");
        s->position+=n;return s->position>=s->prompt.size()?JNI_TRUE:JNI_FALSE;
    }catch(const std::exception &e){fail(env,e.what());return JNI_FALSE;}
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_localcaption_instant_CpuTranslator_nativeNext(JNIEnv *env,jclass,jlong handle){
    try{
        auto s=session(handle);auto vocab=llama_model_get_vocab(s->model);
        llama_token token=llama_sampler_sample(s->sampler,s->context,-1);
        if(llama_vocab_is_eog(vocab,token))return nullptr;
        std::vector<char> piece(256);int n=llama_token_to_piece(vocab,token,piece.data(),piece.size(),0,false);
        if(n<0){piece.resize(-n);n=llama_token_to_piece(vocab,token,piece.data(),piece.size(),0,false);}
        if(n<0)throw std::runtime_error("CPU token decoding failed");
        auto batch=llama_batch_get_one(&token,1);if(llama_decode(s->context,batch)!=0)throw std::runtime_error("CPU translation decoding failed");
        jbyteArray out=env->NewByteArray(n);if(out)env->SetByteArrayRegion(out,0,n,reinterpret_cast<const jbyte*>(piece.data()));return out;
    }catch(const std::exception &e){fail(env,e.what());return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_com_localcaption_instant_CpuTranslator_nativeClose(JNIEnv*,jclass,jlong handle){delete reinterpret_cast<Session*>(handle);}
