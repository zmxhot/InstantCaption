package com.localcaption.instant;

/** Stable error categories, independent of translated UI strings and native messages. */
final class ModelFailure extends Exception {
    enum Kind { MEMORY, HOT, MISSING, LIMIT, TIMEOUT, RUNTIME }
    final Kind kind;
    ModelFailure(Kind kind){super(kind.name());this.kind=kind;}
    static Kind classify(Throwable error){
        for(Throwable cause=error;cause!=null;cause=cause.getCause()){
            if(cause instanceof ModelFailure)return ((ModelFailure)cause).kind;
            if(cause instanceof TranslationRouter.ModelMissingException)return Kind.MISSING;
            if(cause instanceof OutOfMemoryError)return Kind.MEMORY;
        }
        return Kind.RUNTIME;
    }
    static int resource(Kind kind){return switch(kind){
        case MEMORY -> R.string.recovery_memory;
        case HOT -> R.string.recovery_hot;
        case MISSING -> R.string.recovery_missing;
        case LIMIT -> R.string.recovery_limit;
        case TIMEOUT -> R.string.recovery_timeout;
        case RUNTIME -> R.string.recovery_runtime;
    };}
}
