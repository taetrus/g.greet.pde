package com.kk.greet.imp;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;

import com.kk.greet.api.IGreet;

@Component
public class Greet implements IGreet {

	@Activate
	public void start() {
		System.out.println("Greet.start()");
	}

	@Override
	public void greet() {
		System.out.println("Greet.greet()");
	}

}
