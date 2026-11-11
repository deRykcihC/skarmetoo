package com.deryk.skarmetoo.network

import java.io.IOException

class HuggingFaceAuthenticationRequiredException :
    IOException(
        "Sign in to Hugging Face and accept the model's license agreement to download this model.")
