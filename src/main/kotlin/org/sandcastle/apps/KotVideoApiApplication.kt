package org.sandcastle.apps

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Paths

@SpringBootApplication
@ComponentScan
class KotVideoApiApplication

fun main(args: Array<String>) {
	runApplication<KotVideoApiApplication>(*args)
}

@RestController
@RequestMapping("/videos")
class VideoResource {

	@Value("\${video.home}")
	val videoHome: String = "/tmp/"

	@GetMapping("/start")
	fun startVideoStream(request: HttpServletRequest, response: HttpServletResponse) {
		val fileName = request.getParameter("fl")
		MultipartFileSender.fromPath(Paths.get(videoHome + fileName))
			.with(request)
			.with(response)
			.serveResource()
	}
}