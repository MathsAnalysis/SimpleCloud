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

package eu.thesimplecloud.module.rest.auth.controller

import com.auth0.jwt.JWT
import com.auth0.jwt.interfaces.JWTVerifier
import eu.thesimplecloud.module.rest.annotation.RequestBody
import eu.thesimplecloud.module.rest.annotation.RequestMapping
import eu.thesimplecloud.module.rest.annotation.RequestType
import eu.thesimplecloud.module.rest.annotation.RestController
import eu.thesimplecloud.module.rest.auth.AuthService
import eu.thesimplecloud.module.rest.controller.IController
import io.javalin.http.Context

/**
 * Created by IntelliJ IDEA.
 * Date: 05.10.2020
 * Time: 13:01
 * @author Frederick Baier
 */
@RestController("auth/")
class AuthController(
    private val authService: AuthService
) : IController {

    @RequestMapping(RequestType.POST, "login/")
    fun handleLogin(@RequestBody login: LoginDto, context: Context) {
        val token = authService.handleLogin(login)
        if (token == null) {
            context.status(401)
            context.result("Username or password wrong!")
        } else {
            context.json(JWT.decode(token))
        }
    }

}