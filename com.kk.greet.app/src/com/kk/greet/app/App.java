package com.kk.greet.app;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.kk.greet.api.IGreet;

@Component
public class App {

	@Reference
	public IGreet greetService;

	@Activate
	public void start() {
		System.out.println("App.start()");

		greetService.greet();
	}
}
