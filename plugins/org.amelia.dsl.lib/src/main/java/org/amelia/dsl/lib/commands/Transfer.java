/*
 * Copyright © 2015 Universidad Icesi
 * 
 * This file is part of the Amelia library.
 * 
 * The Amelia library is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 * 
 * The Amelia library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with the Amelia library. If not, see <http://www.gnu.org/licenses/>.
 */
package org.amelia.dsl.lib.commands;

import org.amelia.dsl.lib.descriptors.AssetBundle;
import org.amelia.dsl.lib.descriptors.Host;
import org.amelia.dsl.lib.util.Log;

/**
 * @author Miguel Jiménez - Initial contribution and API
 */
public class Transfer extends Command<Void> {

	public Transfer(final Host host, final AssetBundle bundle) {
		super(host, bundle);
	}

	@Override
	public Void call() throws Exception {

		Host host = super.host;
		AssetBundle descriptor = (AssetBundle) super.descriptor;

		try {
			host.ftp().upload(descriptor);
			Log.ok(host, descriptor.doneMessage());
		} catch (Exception e) {
			Log.error(host, descriptor.failMessage());
			throw e;
		}

		return null;
	}

}