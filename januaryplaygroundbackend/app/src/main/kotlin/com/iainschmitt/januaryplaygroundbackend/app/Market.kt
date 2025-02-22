package com.iainschmitt.januaryplaygroundbackend.app

import org.slf4j.Logger

class Market(private val db: DatabaseHelper, private val secure: Boolean, private val wsUserMap: WsUserMap, private val logger: Logger){
    //TODO: Before any of these endpoints, a middleware function should be run to
}