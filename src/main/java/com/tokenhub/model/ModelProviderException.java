package com.tokenhub.model;
public class ModelProviderException extends RuntimeException { private final boolean retryable; public ModelProviderException(String message,boolean retryable,Throwable cause){super(message,cause);this.retryable=retryable;} public boolean retryable(){return retryable;} }
