/*
 * MIT License
 *
 * Copyright (C) 2020-2022 The SimpleCloud authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software,
 * and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package eu.thesimplecloud.module.rest.javalin

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import eu.thesimplecloud.clientserverapi.lib.json.PacketExclude
import eu.thesimplecloud.jsonlib.GsonCreator
import eu.thesimplecloud.module.rest.annotation.WebExclude
import eu.thesimplecloud.module.rest.auth.AuthService
import eu.thesimplecloud.module.rest.auth.JwtProvider
import eu.thesimplecloud.module.rest.auth.Roles
import eu.thesimplecloud.module.rest.auth.controller.AuthController
import eu.thesimplecloud.module.rest.auth.createRolesMapping
import eu.thesimplecloud.module.rest.controller.ControllerHandler
import eu.thesimplecloud.module.rest.controller.RequestMethodData
import eu.thesimplecloud.module.rest.defaultcontroller.UserController
import eu.thesimplecloud.module.rest.defaultcontroller.dump.DumpController
import eu.thesimplecloud.module.rest.defaultcontroller.filemanager.FileManagerController
import eu.thesimplecloud.module.rest.defaultcontroller.group.ServiceGroupActionController
import eu.thesimplecloud.module.rest.defaultcontroller.group.ServiceGroupController
import eu.thesimplecloud.module.rest.defaultcontroller.player.PlayerController
import eu.thesimplecloud.module.rest.defaultcontroller.service.ServiceActionController
import eu.thesimplecloud.module.rest.defaultcontroller.service.ServiceController
import eu.thesimplecloud.module.rest.defaultcontroller.template.TemplateController
import eu.thesimplecloud.module.rest.defaultcontroller.uptime.UptimeController
import eu.thesimplecloud.module.rest.defaultcontroller.version.VersionController
import eu.thesimplecloud.module.rest.defaultcontroller.wrapper.WrapperController
import io.javalin.Javalin
import io.javalin.http.HandlerType
import javalinjwt.JWTAccessManager
import javalinjwt.JavalinJWT
import javax.management.relation.Role

/**
 * Created by IntelliJ IDEA.
 * Date: 04.10.2020
 * Time: 18:01
 * @author Frederick Baier
 */
class RestServer(port: Int) {

    private val authService = AuthService()

    val controllerHandler = ControllerHandler(this)

    private val app = Javalin.create().start(port)

    val webGson = GsonCreator().excludeAnnotations(WebExclude::class.java, PacketExclude::class.java).create()

    init {
        instance = this

        JWTAccessManager("role", createRolesMapping(), Roles.ANYONE)


        app.before(JavalinJWT.createHeaderDecodeHandler(JwtProvider.instance.provider))
        app.before { ctx ->
            ctx.header("Access-Control-Allow-Headers", "*")
            ctx.header("Access-Control-Allow-Origin", "*")
            ctx.header("Access-Control-Allow-Methods", "GET, PUT, POST, DELETE, OPTIONS")
            ctx.header("Content-Type", "application/json; charset=utf-8")
        }


        app.options("/*", {
            it.status(200)
        }, Roles.ANYONE)

        controllerHandler.registerController(AuthController(this.authService))
        controllerHandler.registerController(UserController(this.authService))
        controllerHandler.registerController(ServiceGroupController())
        controllerHandler.registerController(DumpController())
        controllerHandler.registerController(ServiceGroupActionController())
        controllerHandler.registerController(ServiceController())
        controllerHandler.registerController(ServiceActionController())
        controllerHandler.registerController(TemplateController())
        controllerHandler.registerController(WrapperController())
        controllerHandler.registerController(PlayerController())
        controllerHandler.registerController(VersionController())
        controllerHandler.registerController(FileManagerController())
        controllerHandler.registerController(UptimeController())
    }

    fun registerRequestMethod(requestMethodData: RequestMethodData) {
        val requestHandler = JavalinRequestHandler(requestMethodData, authService)
        addToJavalin(requestHandler)
    }

    private fun addToJavalin(requestHandler: JavalinRequestHandler) {
        val requestMethodData = requestHandler.requestMethodData
        val path = requestMethodData.path

        when (requestMethodData.requestType.name.uppercase()) {
            "GET" -> app.get(path, requestHandler, Roles.ANYONE)
            "POST" -> app.post(path, requestHandler, Roles.ANYONE)
            "PUT" -> app.put(path, requestHandler, Roles.ANYONE)
            "DELETE" -> app.delete(path, requestHandler, Roles.ANYONE)
            "PATCH" -> app.patch(path, requestHandler, Roles.ANYONE)
            "HEAD" -> app.head(path, requestHandler, Roles.ANYONE)
            "OPTIONS" -> app.options(path, requestHandler, Roles.ANYONE)
            else -> {
                println("Metodo HTTP non supportato: ${requestMethodData.requestType.name}")
            }
        }
    }
    fun shutdown() {
        app.stop()
    }


    companion object {
        lateinit var instance: RestServer
    }
}