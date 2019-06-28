/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package qupath.lib.images.servers;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import javax.imageio.ImageIO;

import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.regions.RegionRequest;

/**
 * Implementation of an ImageServer using Java's ImageIO.
 * <p>
 * In truth, this isn't actually used for much... and is quite untested.
 * 
 * @author Pete Bankhead
 *
 */
public class ImageIoImageServer extends AbstractImageServer<BufferedImage> {
	
	private ImageServerMetadata originalMetadata;

	private BufferedImage img;
	private URI uri;
	private String imageName;
	private String[] args;
	
	private final int[] rgbTypes = new int[] {
			BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_ARGB_PRE, BufferedImage.TYPE_INT_RGB
	};
	
	/**
	 * Create an {@code ImageServer<BufferedImage>} using an image that has been provided directly.
	 * 
	 * @param uri
	 * @param path
	 * @param imageName
	 * @param img
	 * @param args
	 */
	public ImageIoImageServer(final URI uri, String path, final String imageName, final BufferedImage img, String...args) {
		super(BufferedImage.class);
		this.uri = uri;
		this.img = img;
		this.imageName = imageName;

		// Create metadata objects
		PixelType pixelType;
		switch (img.getRaster().getDataBuffer().getDataType()) {
		case DataBuffer.TYPE_BYTE:
			pixelType = PixelType.UINT8;
			break;
		case DataBuffer.TYPE_USHORT:
			pixelType = PixelType.UINT16;
			break;
		case DataBuffer.TYPE_FLOAT:
			pixelType = PixelType.FLOAT32;
			break;
		case DataBuffer.TYPE_DOUBLE:
			pixelType = PixelType.FLOAT64;
			break;
		case DataBuffer.TYPE_SHORT:
			pixelType = PixelType.INT16;
			break;
		case DataBuffer.TYPE_INT:
			pixelType = PixelType.INT32;
			break;
		default:
			throw new IllegalArgumentException("Unsupported image data type " + img.getRaster().getDataBuffer().getDataType());
		}
		int nChannels = img.getSampleModel().getNumBands();
		boolean isRGB = false;
		for (int type : rgbTypes) {
			isRGB = isRGB | type == img.getType();
		}
		
		this.args = args;
		originalMetadata = new ImageServerMetadata.Builder(getClass())
				.width(img.getWidth())
				.height(img.getHeight())
//				.args(args)
				.rgb(isRGB)
				.pixelType(pixelType)
				.channels(ImageChannel.getDefaultChannelList(nChannels)).
				build();
	}
	
	/**
	 * Create an {@code ImageServer<BufferedImage>} after first reading the image using ImageIO.
	 * 
	 * @param uri
	 * @throws MalformedURLException
	 * @throws IOException
	 */
	public ImageIoImageServer(URI uri, String...args) throws MalformedURLException, IOException {
		this(uri, uri.toString(), null, ImageIO.read(uri.toURL()), args);
	}

	@Override
	public BufferedImage readBufferedImage(RegionRequest request) {
		// TODO: Check this - there is a very real possibility it's completely wrong!
		double downsampleFactor = request.getDownsample();
		int w = (int)(request.getWidth() / downsampleFactor + .5);
		int h = (int)(request.getHeight() / downsampleFactor + .5);
		BufferedImage img2 = new BufferedImage(w, h, img.getType());
		Graphics2D g2d = img2.createGraphics();
		g2d.translate(-request.getX(), -request.getY());
		if (downsampleFactor != 1)
			g2d.scale(1.0/downsampleFactor, 1.0/downsampleFactor);
		g2d.drawImage(img, 0, 0, null);
		g2d.dispose();
		return img2;
	}

	@Override
	public String getServerType() {
		return "BufferedImage";
	}

	@Override
	public String getDisplayedImageName() {
		if (imageName == null)
			return super.getDisplayedImageName();
		return imageName;
	}
	
	@Override
	public ImageServerMetadata getOriginalMetadata() {
		return originalMetadata;
	}
	
	@Override
	public ServerBuilder<BufferedImage> getBuilder() {
		return ImageServerBuilder.DefaultImageServerBuilder.createInstance(
				ImageIoImageServerBuilder.class,
				getMetadata(),
				uri,
				args);
	}

}
