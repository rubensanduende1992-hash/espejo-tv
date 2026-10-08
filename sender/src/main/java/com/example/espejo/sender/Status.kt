package com.example.espejo.sender

// Canal simple para mostrar en pantalla lo que hace el servicio
object Status {
    @Volatile var listener: ((String) -> Unit)? = null
    fun post(msg: String) {
        listener?.invoke(msg)
    }
}
