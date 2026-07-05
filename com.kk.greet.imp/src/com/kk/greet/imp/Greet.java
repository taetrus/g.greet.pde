package com.kk.greet.imp;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;

import com.kk.greet.api.IGreet;

// Encoding canary — must stay compilable: çğıöşü ÇĞİÖŞÜ
@Component
public class Greet implements IGreet {

	@Activate
	public void start() {
		System.out.println(MessageFormatter.format("start"));
	}

	@Override
	public void greet() {
		System.out.println(MessageFormatter.format("greet"));
	}

}
