/*
 *   $Id$
 *
 *   Copyright 2006 University of Dundee. All rights reserved.
 *   Use is subject to license terms supplied in LICENSE.txt
 */

package ome.services;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import ome.annotations.RolesAllowed;
import ome.api.IPixels;
import ome.api.IRenderingSettings;
import ome.api.IRepositoryInfo;
import ome.api.IScale;
import ome.api.ServiceInterface;
import ome.api.ThumbnailStore;
import ome.api.local.LocalCompress;
import ome.conditions.ApiUsageException;
import ome.conditions.InternalException;
import ome.conditions.ResourceError;
import ome.conditions.ValidationException;
import ome.io.nio.OriginalFileMetadataProvider;
import ome.io.nio.PixelBuffer;
import ome.io.nio.PixelsService;
import ome.io.nio.ThumbnailService;
import ome.logic.AbstractLevel2Service;
import ome.model.core.Pixels;
import ome.model.display.RenderingDef;
import ome.model.display.Thumbnail;
import ome.model.enums.Family;
import ome.model.enums.RenderingModel;
import ome.model.meta.Session;
import ome.parameters.Parameters;
import ome.system.EventContext;
import ome.system.SimpleEventContext;
import ome.util.ImageUtil;
import omeis.providers.re.Renderer;
import omeis.providers.re.data.PlaneDef;
import omeis.providers.re.data.RegionDef;
import omeis.providers.re.quantum.QuantizationException;
import omeis.providers.re.quantum.QuantumFactory;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.perf4j.StopWatch;
import org.perf4j.commonslog.CommonsLogStopWatch;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provides methods for directly querying object graphs. The service is entirely
 * read/write transactionally because of the requirements of rendering engine
 * lazy object creation where rendering settings are missing.
 * 
 * @author Chris Allan &nbsp;&nbsp;&nbsp;&nbsp; <a
 *         href="mailto:callan@blackcat.ca">callan@blackcat.ca</a>
 * @version 3.0 <small> (<b>Internal version:</b> $Rev$ $Date$) </small>
 * @since 3.0
 * 
 */
@Transactional(readOnly = true)
public class ThumbnailBean extends AbstractLevel2Service
    implements ThumbnailStore, Serializable
{
    /**
     * 
     */
    private static final long serialVersionUID = 3047482880497900069L;

    /** The logger for this class. */
    private transient static Log log = LogFactory.getLog(ThumbnailBean.class);

    /** The renderer that this service uses for thumbnail creation. */
    private transient Renderer renderer;

    /** The scaling service will be used to scale buffered images. */
    private transient IScale iScale;

    /** The pixels service, will be used to load pixels and settings. */
    private transient IPixels iPixels;

    /** The service used to retrieve the pixels data. */
    private transient PixelsService pixelDataService;

    /** The ROMIO thumbnail service. */
    private transient ThumbnailService ioService;

    /** The disk space checking service. */
    private transient IRepositoryInfo iRepositoryInfo;

    /** The JPEG compression service. */
    private transient LocalCompress compressionService;

    /** The rendering settings service. */
    private transient IRenderingSettings settingsService;

    /** The list of all families supported by the {@link Renderer}. */
    private transient List<Family> families;

    /** The list of all rendering models supported by the {@link Renderer}. */
    private transient List<RenderingModel> renderingModels;

    /** If the file service checking for disk overflow. */
    private transient boolean diskSpaceChecking;

    /** If the renderer is dirty. */
    private Boolean dirty = true;

    /** If the settings {@link metadata} is dirty. */
    private Boolean dirtyMetadata = false;

    /** The pixels instance that the service is currently working on. */
    private Pixels pixels;

    /** ID of the pixels instance that the service is currently working on. */
    private Long pixelsId;

    /** The rendering settings that the service is currently working with. */
    private RenderingDef settings;

    /** The thumbnail metadata that the service is currently working with. */
    private Thumbnail thumbnailMetadata;

    /** The thumbnail metadata context. */
    private ThumbnailCtx ctx;

    /** The default X-width for a thumbnail. */
    public static final int DEFAULT_X_WIDTH = 48;

    /** The default Y-width for a thumbnail. */
    public static final int DEFAULT_Y_WIDTH = 48;

    /** The default compression quality in fractional percent. */
    public static final float DEFAULT_COMPRESSION_QUALITY = 0.85F;

    /** The default MIME type. */
    public static final String DEFAULT_MIME_TYPE = "image/jpeg";

    /**
     * read-write lock to prevent READ-calls during WRITE operations.
     *
     * It is safe for the lock to be serialized. On deserialization, it will
     * be in the unlocked state.
     */
    private final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();

    /** Notification that the bean has just returned from passivation. */
    private transient boolean wasPassivated = false;

    /** default constructor */
    public ThumbnailBean() {}

    /**
     * overriden to allow Spring to set boolean
     * @param checking
     */
    public ThumbnailBean(boolean checking) {
        this.diskSpaceChecking = checking;
    }

    public Class<? extends ServiceInterface> getServiceInterface() {
        return ThumbnailStore.class;
    }

    // ~ Lifecycle methods
    // =========================================================================

    // See documentation on JobBean#passivate
    @RolesAllowed("user")
    @Transactional(readOnly = true)
    public void passivate() {
        log.debug("***** Passivating... ******");

        rwl.writeLock().lock();
        try {
            if (renderer != null) {
                renderer.close();
            }
            renderer = null;
        } finally {
            rwl.writeLock().unlock();
        }
    }

    // See documentation on JobBean#activate
    @RolesAllowed("user")
    @Transactional(readOnly = true)
    public void activate() {
        log.debug("***** Returning from passivation... ******");

        rwl.writeLock().lock();
        try {
            wasPassivated = true;
        } finally {
            rwl.writeLock().unlock();
        }
    }

    @RolesAllowed("user")
    public void close() {
        rwl.writeLock().lock();
        log.debug("Closing thumbnail bean");
        try {
            if (renderer != null) {
                renderer.close();
            }
            ctx = null;
            settings = null;
            pixels = null;
            thumbnailMetadata = null;
            renderer = null;
            iScale = null;
            ioService = null;
        } finally {
            rwl.writeLock().unlock();
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see ome.api.StatefulServiceInterface#getCurrentEventContext()
     */
    public EventContext getCurrentEventContext() {
        return new SimpleEventContext(getSecuritySystem().getEventContext());
    }

    /*
     * (non-Javadoc)
     * 
     * @see ome.api.ThumbnailStore#setPixelsId(long)
     */
    @RolesAllowed("user")
    @Transactional(readOnly = false)
    public boolean setPixelsId(long id)
    {
        // If we've had a pixels set change, reset our stateful objects.
        if ((pixels != null && pixels.getId() != id) || pixels == null)
        {
            resetMetadata();
            newContext();
        }
        Set<Long> pixelsIds = new HashSet<Long>();
        pixelsIds.add(id);
        ctx.loadAndPrepareRenderingSettings(pixelsIds);
        pixels = ctx.getPixels(id);
        pixelsId = pixels.getId();
        settings = ctx.getSettings(id);
        if (ctx.hasSettings(id))
        {
            return true;
        }
        return false;
    }

    /**
     * Retrieves a list of the families supported by the {@link Renderer}
     * either from instance variable cache or the database.
     * @return See above.
     */
    private List<Family> getFamilies()
    {
        if (families == null)
        {
            families = iPixels.getAllEnumerations(Family.class);
        }
        return families;
    }

    /**
     * Retrieves a list of the rendering models supported by the 
     * {@link Renderer} either from instance variable cache or the database.
     * @return See above.
     */
    private List<RenderingModel> getRenderingModels()
    {
        if (renderingModels == null)
        {
            renderingModels = iPixels.getAllEnumerations(RenderingModel.class);
        }
        return renderingModels;
    }

    /**
     * Retrieves a deep copy of the pixels set and rendering settings as
     * required for a rendering event and creates a renderer. This method
     * should only be called if a rendering event is required.
     */
    private void load()
    {
        if (renderer != null)
        {
            renderer.close();
        }
        pixels = iPixels.retrievePixDescription(pixels.getId());
        settings = iPixels.loadRndSettings(settings.getId());
        OriginalFileMetadataProvider metadataProvider =
            new OmeroOriginalFileMetadataProvider(iQuery);
        PixelBuffer buffer = 
            pixelDataService.getPixelBuffer(pixels, metadataProvider, false);
        List<Family> families = getFamilies();
        List<RenderingModel> renderingModels = getRenderingModels();
        QuantumFactory quantumFactory = new QuantumFactory(families);
        renderer = new Renderer(quantumFactory, renderingModels, pixels,
                settings, buffer);
        dirty = false;
    }

    /* (non-Javadoc)
     * @see ome.api.ThumbnailStore#setRenderingDefId(java.lang.Long)
     */
    @RolesAllowed("user")
    public void setRenderingDefId(long id)
    {
        errorIfNullPixels();
        ctx.loadAndPrepareRenderingSettings(pixelsId, id);
        settings = ctx.getSettings(pixelsId);
        // Handle cases where this new settings is not owned by us so that
        // retrieval of thumbnail metadata is done based on the owner of the
        // settings not the owner of the session. (#2274 Part I) 
        ctx.setUserId(settings.getDetails().getOwner().getId());
    }

    /**
     * Pixels service Bean injector.
     * 
     * @param iPixels
     *            an <code>IPixels</code>.
     */
    public void setPixelDataService(PixelsService pixelDataService) {
        getBeanHelper().throwIfAlreadySet(this.pixelDataService, pixelDataService);
        this.pixelDataService = pixelDataService;
    }

    /**
     * Pixels service Bean injector.
     * 
     * @param iPixels
     *            an <code>IPixels</code>.
     */
    public void setIPixels(IPixels iPixels) {
        getBeanHelper().throwIfAlreadySet(this.iPixels, iPixels);
        this.iPixels = iPixels;
    }

    /**
     * Scale service Bean injector.
     * 
     * @param iScale
     *            an <code>IScale</code>.
     */
    public void setScaleService(IScale iScale) {
        getBeanHelper().throwIfAlreadySet(this.iScale, iScale);
        this.iScale = iScale;
    }

    /**
     * I/O service (ThumbnailService) Bean injector.
     * 
     * @param ioService
     *            a <code>ThumbnailService</code>.
     */
    public void setIoService(ThumbnailService ioService) {
        getBeanHelper().throwIfAlreadySet(this.ioService, ioService);
        this.ioService = ioService;
    }

    /**
     * Disk Space Usage service Bean injector
     * @param iRepositoryInfo
     *   		  	an <code>IRepositoryInfo</code>
     */
    public final void setIRepositoryInfo(IRepositoryInfo iRepositoryInfo) {
        getBeanHelper().throwIfAlreadySet(this.iRepositoryInfo, iRepositoryInfo);
        this.iRepositoryInfo = iRepositoryInfo;
    }

    /**
     * Compression service Bean injector.
     * 
     * @param compressionService
     *            an <code>ICompress</code>.
     */
    public void setCompressionService(LocalCompress compressionService) {
        getBeanHelper().throwIfAlreadySet(this.compressionService,
                compressionService);
        this.compressionService = compressionService;
    }

    /**
     * Rendering settings service Bean injector.
     * 
     * @param settingsService
     *            an <code>IRenderingSettings</code>.
     */
    public void setSettingsService(IRenderingSettings settingsService) {
        getBeanHelper().throwIfAlreadySet(this.settingsService,
                settingsService);
        this.settingsService = settingsService;
    }

    /**
     * Compresses a buffered image thumbnail to disk.
     * 
     * @param thumb
     *            the thumbnail metadata.
     * @param image
     *            the thumbnail's buffered image.
     * @throws IOException
     *             if there is a problem writing to disk.
     */
    private void compressThumbnailToDisk(Thumbnail thumb, BufferedImage image)
    throws IOException {

        if (diskSpaceChecking) {
            iRepositoryInfo.sanityCheckRepository();
        }

        FileOutputStream stream = ioService.getThumbnailOutputStream(thumb);
        compressionService.compressToStream(image, stream);
        stream.close();
    }

    /**
     * Returns the Id of the currently logged in user.
     * Returns owner of the share while in share
     * @return See above.
     */
    private Long getCurrentUserId()
    {
        Long shareId = getSecuritySystem().getEventContext().getCurrentShareId();
        if (shareId != null) {
            Session s = iQuery.get(Session.class, shareId);
            return s.getOwner().getId();
        } 
        return getSecuritySystem().getEventContext().getCurrentUserId();
    }

    /**
     * Checks that sizeX and sizeY are not out of range for the active pixels
     * set and returns a set of valid dimensions.
     * 
     * @param sizeX
     *            the X-width for the requested thumbnail.
     * @param sizeY
     *            the Y-width for the requested thumbnail.
     * @return A set of valid XY dimensions.
     */
    private Dimension sanityCheckThumbnailSizes(Integer sizeX, Integer sizeY) {
        // Sanity checks
        if (sizeX == null) {
            sizeX = DEFAULT_X_WIDTH;
        }
        if (sizeX < 0) {
            throw new ApiUsageException("sizeX is negative");
        }
        if (sizeY == null) {
            sizeY = DEFAULT_Y_WIDTH;
        }
        if (sizeY < 0) {
            throw new ApiUsageException("sizeY is negative");
        }
        return new Dimension(sizeX, sizeY);
    }

    /**
     * Creates a scaled buffered image from the active pixels set.
     * 
     * @param def
     *            the rendering settings to use for buffered image creation.
     * @param theZ the optical section (offset across the Z-axis) requested. 
     * <pre>null</pre> signifies the rendering engine default.
     * @param theT the timepoint (offset across the T-axis) requested. 
     * <pre>null</pre> signifies the rendering engine default.
     * @return a scaled buffered image.
     */
    private BufferedImage createScaledImage(Integer theZ, Integer theT)
    {
        // Ensure that we have a valid state for rendering
        errorIfInvalidState();

        // Retrieve our rendered data
        if (theZ == null)
            theZ = settings.getDefaultZ();
        if (theT == null)
            theT = settings.getDefaultT();
        PlaneDef pd = new PlaneDef(PlaneDef.XY, theT);
        pd.setZ(theZ);
        // Use a resolution level that matches our requested size if we can
        PixelBuffer pixelBuffer = renderer.getPixels();
        int originalSizeX = pixels.getSizeX();
        int originalSizeY = pixels.getSizeY();
        if (pixelBuffer.getResolutionLevels() > 1)
        {
            int resolutionLevel = pixelBuffer.getResolutionLevels();
            int pixelBufferSizeX = pixelBuffer.getSizeX();
            int pixelBufferSizeY = pixelBuffer.getSizeY();
            while (resolutionLevel > 0)
            {
                resolutionLevel--;
                pixelBuffer.setResolutionLevel(resolutionLevel);
                pixelBufferSizeX = pixelBuffer.getSizeX();
                pixelBufferSizeY = pixelBuffer.getSizeY();
                if (pixelBufferSizeX <= thumbnailMetadata.getSizeX()
                    || pixelBufferSizeY <= thumbnailMetadata.getSizeY())
                {
                    break;
                }
            }
            log.info(String.format("Using resolution level %d -- %dx%d",
                    resolutionLevel, pixelBufferSizeX, pixelBufferSizeY));
            renderer.setResolutionLevel(resolutionLevel);
            pixels.setSizeX(pixelBufferSizeX);
            pixels.setSizeY(pixelBufferSizeY);
        }

        // Render the planes and translate to a buffered image
        BufferedImage image;
        try
        {
            int[] buf = renderer.renderAsPackedInt(pd, null);
            image = ImageUtil.createBufferedImage(
                    buf, pixels.getSizeX(), pixels.getSizeY());

            // Finally, scale our image using scaling factors (percentage).
            float xScale = (float)
                    thumbnailMetadata.getSizeX() / pixels.getSizeX();
            float yScale = (float)
                    thumbnailMetadata.getSizeY() / pixels.getSizeY();
            return iScale.scaleBufferedImage(image, xScale, yScale);
        } 
        catch (IOException e)
        {
            ResourceError re = new ResourceError(
                    "IO error while rendering: " + e.getMessage());
            re.initCause(e);
            throw re;
        }
        catch (QuantizationException e)
        {
            InternalException ie = new InternalException(
                    "QuantizationException while rendering: " + e.getMessage());
            ie.initCause(e);
            throw ie;
        }
        finally
        {
            // Reset to our original dimensions (#5075)
            pixels.setSizeX(originalSizeX);
            pixels.setSizeY(originalSizeY);
        }
    }

    /**
     * Creates a new thumbnail context.
     */
    private void newContext()
    {
        resetMetadata();
        ctx = new ThumbnailCtx(
                iQuery, iUpdate, iPixels, settingsService, ioService,
                sec, getCurrentUserId());
    }

    /**
     * Resets the current metadata state.
     */
    private void resetMetadata()
    {
        pixels = null;
        pixelsId = null;
        settings = null;
        dirty = true;
        dirtyMetadata = false;
        thumbnailMetadata = null;
        // Be as explicit as possible when closing the renderer to try and
        // avoid re-use where we don't want it. (#2075 and #2274 Part II)
        if (renderer != null)
        {
            renderer.close();
        }
        renderer = null;
    }

    protected void errorIfInvalidState()
    {
        errorIfNullPixelsAndRenderingDef();
        if ((renderer == null && wasPassivated) || dirty)
        {
            load();
        }
        else if (renderer == null)
        {
            throw new InternalException(
            "Thumbnail service state corruption: Renderer missing.");
        }
    }

    protected void errorIfNullPixelsAndRenderingDef()
    {
        errorIfNullPixels();
        errorIfNullRenderingDef();
    }

    protected void errorIfNullPixels()
    {
        if (pixels == null)
        {
            throw new ApiUsageException(
            "Thumbnail service not ready: Pixels not set.");
        }
    }

    protected void errorIfNullRenderingDef()
    {
        errorIfNullPixels();
        if (settings == null && sec.isGraphCritical())
        {
            long ownerId = pixels.getDetails().getOwner().getId();
            throw new ResourceError(String.format(
                    "The owner id:%d has not viewed the Pixels set id:%d, " +
                    "rendering settings are missing.", ownerId, pixelsId));
        }
        else if (settings == null)
        {
            throw new ome.conditions.InternalException(
                    "Fatal error retrieving rendering settings or settings " +
                    "not loaded for Pixels set id:" + pixelsId);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see ome.api.ThumbnailStore#createThumbnail(ome.model.core.Pixels,
     *      ome.model.display.RenderingDef, java.lang.Integer,
     *      java.lang.Integer)
     */
    @RolesAllowed("user")
    @Transactional(readOnly = false)
    public void createThumbnail(Integer sizeX, Integer sizeY)
    {
        try
        {
            // Set defaults and sanity check thumbnail sizes
            if (sizeX == null) {
                sizeX = DEFAULT_X_WIDTH;
            }
            if (sizeY == null) {
                sizeY = DEFAULT_Y_WIDTH;
            }
            Dimension dimensions = sanityCheckThumbnailSizes(sizeX, sizeY);
            Set<Long> pixelsIds = new HashSet<Long>();
            pixelsIds.add(pixelsId);
            ctx.loadAndPrepareMetadata(pixelsIds, dimensions);
            thumbnailMetadata = ctx.getMetadata(pixels.getId());
            thumbnailMetadata = _createThumbnail();
            if (dirtyMetadata)
            {
                thumbnailMetadata = iUpdate.saveAndReturnObject(thumbnailMetadata);
            }

            // Ensure that we do not have "dirty" pixels or rendering settings 
            // left around in the Hibernate session cache.
            iQuery.clear();
        }
        finally
        {
            dirtyMetadata = false;
        }
    }

    /** Actually does the work specified by {@link createThumbnail()}.*/
    private Thumbnail _createThumbnail() {
        StopWatch s1 = new CommonsLogStopWatch("omero._createThumbnail");
        if (thumbnailMetadata == null) {
            throw new ValidationException("Missing thumbnail metadata.");
        } else if (ctx.dirtyMetadata(pixels.getId())) {
            // Increment the version of the thumbnail so that its
            // update event has a timestamp equal to or after that of
            // the rendering settings. FIXME: This should be 
            // implemented using IUpdate.touch() or similar once that 
            // functionality exists.
            thumbnailMetadata.setVersion(thumbnailMetadata.getVersion() + 1);
            Pixels unloadedPixels = new Pixels(pixels.getId(), false);
            thumbnailMetadata.setPixels(unloadedPixels);
            dirtyMetadata = true;
        }
        // dirtyMetadata is left false here because we may be creating a
        // thumbnail for the first time and the Thumbnail object has just been
        // created upstream of us.

        BufferedImage image = createScaledImage(null, null);
        try {
            compressThumbnailToDisk(thumbnailMetadata, image);
            s1.stop();
            return thumbnailMetadata;
        } catch (IOException e) {
            log.error("Thumbnail could not be compressed.", e);
            throw new ResourceError(e.getMessage());
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see ome.api.ThumbnailStore#createThumbnails(ome.model.core.Pixels,
     *      ome.model.display.RenderingDef)
     */
    @RolesAllowed("user")
    @Transactional(readOnly = false)
    public void createThumbnails() {
        try
        {
            List<Thumbnail> thumbnails = ctx.loadAllMetadata(pixelsId);
            for (Thumbnail thumbnail : thumbnails) {
                thumbnailMetadata = thumbnail;
                _createThumbnail();
            }
            // We're doing the update or creation and save as a two step 
            // process due to the possible unloaded Pixels. If we do not, 
            // Pixels will be unloaded and we will hit 
            // IllegalStateException's when checking update events.
            iUpdate.saveArray(thumbnails.toArray(
                    new Thumbnail[thumbnails.size()]));

            // Ensure that we do not have "dirty" pixels or rendering settings
            // left around in the Hibernate session cache.
            iQuery.clear();
        }
        finally
        {
            dirtyMetadata = false;
        }
    }

    @RolesAllowed("user")
    @Transactional(readOnly = false)
    public void createThumbnailsByLongestSideSet(Integer size,
            Set<Long> pixelsIds)
    {
        getThumbnailByLongestSideSet(size, pixelsIds);
    }

    /* (non-Javadoc)
     * @see ome.api.ThumbnailStore#getThumbnailSet(java.lang.Integer, java.lang.Integer, java.util.Set)
     */
    @RolesAllowed("user")
    @Transactional(readOnly = false)
    public Map<Long, byte[]> getThumbnailSet(Integer sizeX, Integer sizeY,
            Set<Long> pixelsIds)
    {
        // Set defaults and sanity check thumbnail sizes
        Dimension checkedDimensions = sanityCheckThumbnailSizes(sizeX, sizeY);

        // Prepare our thumbnail context
        newContext();
        ctx.loadAndPrepareRenderingSettings(pixelsIds);
        ctx.createAndPrepareMissingRenderingSettings(pixelsIds);
        ctx.loadAndPrepareMetadata(pixelsIds, checkedDimensions);

        return retrieveThumbnailSet(pixelsIds);
    }

    @RolesAllowed("user")
    @Transactional(readOnly = false)
    public Map<Long, byte[]> getThumbnailByLongestSideSet(Integer size,
            Set<Long> pixelsIds)
    {
        // Set defaults and sanity check thumbnail sizes
        Dimension checkedDimensions = sanityCheckThumbnailSizes(size, size);
        size = (int) checkedDimensions.getWidth();

        // Prepare our thumbnail context
        newContext();
        ctx.loadAndPrepareRenderingSettings(pixelsIds);
        ctx.createAndPrepareMissingRenderingSettings(pixelsIds);
        ctx.loadAndPrepareMetadata(pixelsIds, size);

        return retrieveThumbnailSet(pixelsIds);
    }

    /**
     * Performs the logic of retrieving a set of thumbnails.
     * @param pixelsIds The Pixels IDs to retrieve thumbnails for.
     * @return Map of Pixels ID vs. thumbnail bytes.
     */
    private Map<Long, byte[]> retrieveThumbnailSet(Set<Long> pixelsIds)
    {
        // Our return value HashMap
        Map<Long, byte[]> toReturn = new HashMap<Long, byte[]>();

        List<Thumbnail> toSave = new ArrayList<Thumbnail>();
        for (Long pixelsId : pixelsIds)
        {
            // Ensure that the renderer has been made dirty otherwise the
            // same renderer will be used to return all thumbnails with dirty
            // metadata. (See #2075).
            resetMetadata();
            try
            {
                if (!ctx.hasSettings(pixelsId))
                {
                    continue;
                }
                pixels = ctx.getPixels(pixelsId);
                pixelsId = pixels.getId();
                settings = ctx.getSettings(pixelsId);
                thumbnailMetadata = ctx.getMetadata(pixelsId);
                try
                {
                    byte[] thumbnail = retrieveThumbnail();
                    toReturn.put(pixelsId, thumbnail);
                    if (dirtyMetadata)
                    {
                        toSave.add(thumbnailMetadata);
                    }
                }
                finally
                {
                    dirtyMetadata = false;
                }
            }
            catch (Throwable t)
            {
                log.warn("Retrieving thumbnail in set for " +
                        "Pixels ID " + pixelsId + " failed.", t);
                toReturn.put(pixelsId, null);
            }
        }
        // We're doing the update or creation and save as a two step 
        // process due to the possible unloaded Pixels. If we do not, 
        // Pixels will be unloaded and we will hit 
        // IllegalStateException's when checking update events.
        iUpdate.saveArray(toSave.toArray(new Thumbnail[toSave.size()]));
        // Ensure that we do not have "dirty" pixels or rendering settings left
        // around in the Hibernate session cache.
        iQuery.clear();
        iUpdate.flush();
        return toReturn;
    }

    /*
     * (non-Javadoc)
     * 
     * @see ome.api.ThumbnailStore#getThumbnail(ome.model.core.Pixels,
     *      ome.model.display.RenderingDef, java.lang.Integer,
     *      java.lang.Integer)
     */
    @RolesAllowed("user")
    @Transactional(readOnly = false)
    public byte[] getThumbnail(Integer sizeX, Integer sizeY) {
        errorIfNullPixelsAndRenderingDef();
        Dimension dimensions = sanityCheckThumbnailSizes(sizeX, sizeY);
        // Ensure that we do not have "dirty" pixels or rendering settings 
        // left around in the Hibernate session cache.
        iQuery.clear();
        // Reloading thumbnail metadata because we don't know what may have
        // happened in the database since our last method call.
        Set<Long> pixelsIds = new HashSet<Long>();
        pixelsIds.add(pixelsId);
        ctx.loadAndPrepareMetadata(pixelsIds, dimensions);
        thumbnailMetadata = ctx.getMetadata(pixelsId);
        return retrieveThumbnailAndUpdateMetadata(); 
    }

    /**
     * Creates the thumbnail or retrieves it from cache and updates the
     * thumbnail metadata.
     * @return Thumbnail bytes.
     */
    private byte[] retrieveThumbnailAndUpdateMetadata()
    {
        byte[] thumbnail = retrieveThumbnail();
        if (dirtyMetadata)
        {
            try
            {
                iUpdate.saveObject(thumbnailMetadata);
            }
            finally
            {
                dirtyMetadata = false;
            }
        }
        return thumbnail;
    }

    /**
     * Creates the thumbnail or retrieves it from cache.
     * @return Thumbnail bytes.
     */
    private byte[] retrieveThumbnail()
    {
        try
        {
            boolean cached = ctx.isThumbnailCached(pixels.getId());
            if (cached)
            {
                if (log.isDebugEnabled())
                {
                    log.debug("Cache hit.");
                }
            }
            else
            {
                if (log.isDebugEnabled())
                {
                    log.debug("Cache miss, thumbnail missing or out of date.");
                }
                _createThumbnail();
            }
            byte[] thumbnail = ioService.getThumbnail(thumbnailMetadata);
            return thumbnail;
        }
        catch (IOException e)
        {
            log.error("Could not obtain thumbnail", e);
            throw new ResourceError(e.getMessage());
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see ome.api.ThumbnailStore#getThumbnailByLongestSide(ome.model.core.Pixels,
     *      ome.model.display.RenderingDef, java.lang.Integer)
     */
    @RolesAllowed("user")
    @Transactional(readOnly = false)
    public byte[] getThumbnailByLongestSide(Integer size) {
        errorIfNullPixelsAndRenderingDef();
        // Set defaults and sanity check thumbnail sizes
        Dimension dimensions = sanityCheckThumbnailSizes(size, size);
        size = (int) dimensions.getWidth();

        // Ensure that we do not have "dirty" pixels or rendering settings left
        // around in the Hibernate session cache.
        iQuery.clear();
        // Resetting thumbnail metadata because we don't know what may have
        // happened in the database since or if sizeX and sizeY have changed.
        Set<Long> pixelsIds = new HashSet<Long>();
        pixelsIds.add(pixelsId);
        ctx.loadAndPrepareMetadata(pixelsIds, size);
        thumbnailMetadata = ctx.getMetadata(pixelsId);
        return retrieveThumbnailAndUpdateMetadata();
    }

    /*
     * (non-Javadoc)
     * 
     * @see ome.api.ThumbnailStore#getThumbnailDirect(ome.model.core.Pixels,
     *      ome.model.display.RenderingDef, java.lang.Integer,
     *      java.lang.Integer)
     */
    @RolesAllowed("user")
    public byte[] getThumbnailDirect(Integer sizeX, Integer sizeY)
    {
        // Ensure that we do not have "dirty" pixels or rendering settings 
        // left around in the Hibernate session cache.
        iQuery.clear();
        return retrieveThumbnailDirect(sizeX, sizeY, null, null);
    }

    /**
     * Retrieves a thumbnail directly, not inspecting or interacting with the
     * thumbnail cache.
     * @param sizeX Width of the thumbnail.
     * @param sizeY Height of the thumbnail.
     * @param theZ Optical section to retrieve a thumbnail for.
     * @param theT Timepoint to retrieve a thumbnail for.
     * @return
     */
    private byte[] retrieveThumbnailDirect(Integer sizeX, Integer sizeY,
            Integer theZ, Integer theT)
    {
        errorIfNullPixelsAndRenderingDef();
        // Set defaults and sanity check thumbnail sizes
        Dimension dimensions = sanityCheckThumbnailSizes(sizeX, sizeY);
        thumbnailMetadata = ctx.createThumbnailMetadata(pixels, dimensions);

        BufferedImage image = createScaledImage(theZ, theT);
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        try {
            compressionService.compressToStream(image, byteStream);
            byte[] thumbnail = byteStream.toByteArray();
            return thumbnail;
        } catch (IOException e) {
            log.error("Could not obtain thumbnail direct.", e);
            throw new ResourceError(e.getMessage());
        } finally {
            try {
                byteStream.close();
            } catch (IOException e) {
                log.error("Could not close byte stream.", e);
                throw new ResourceError(e.getMessage());
            }
        }
    }

    /* (non-Javadoc)
     * @see ome.api.ThumbnailStore#getThumbnailForSectionDirect(int, int, java.lang.Integer, java.lang.Integer)
     */
    @RolesAllowed("user")
    public byte[] getThumbnailForSectionDirect(int theZ, int theT,
            Integer sizeX, Integer sizeY)
    {
        // Ensure that we do not have "dirty" pixels or rendering settings 
        // left around in the Hibernate session cache.
        iQuery.clear();
        return retrieveThumbnailDirect(sizeX, sizeY, theZ, theT);
    }

    /** Actually does the work specified by {@link getThumbnailByLongestSideDirect()}.*/
    private byte[] _getThumbnailByLongestSideDirect(Integer size, Integer theZ, 
            Integer theT)
    {
        // Sanity check thumbnail sizes
        Dimension dimensions = sanityCheckThumbnailSizes(size, size);

        dimensions = ctx.calculateXYWidths(pixels, (int) dimensions.getWidth());
        return retrieveThumbnailDirect((int) dimensions.getWidth(),
                (int) dimensions.getHeight(), theZ, theT);
    }

    /*
     * (non-Javadoc)
     * 
     * @see ome.api.ThumbnailStore#getThumbnailByLongestSideDirect(ome.model.core.Pixels,
     *      ome.model.display.RenderingDef, java.lang.Integer)
     */
    @RolesAllowed("user")
    public byte[] getThumbnailByLongestSideDirect(Integer size) {
        // Ensure that we do not have "dirty" pixels or rendering settings 
        // left around in the Hibernate session cache.
        iQuery.clear();
        return _getThumbnailByLongestSideDirect(size, null, null);
    }

    /* (non-Javadoc)
     * @see ome.api.ThumbnailStore#getThumbnailForSectionByLongestSideDirect(int, int, java.lang.Integer)
     */
    @RolesAllowed("user")
    public byte[] getThumbnailForSectionByLongestSideDirect(int theZ, int theT,
            Integer size)
    {
        // Ensure that we do not have "dirty" pixels or rendering settings 
        // left around in the Hibernate session cache.
        iQuery.clear();
        return _getThumbnailByLongestSideDirect(size, theZ, theT);
    }

    /*
     * (non-Javadoc)
     * 
     * @see ome.api.ThumbnailStore#thumbnailExists(ome.model.core.Pixels,
     *      java.lang.Integer, java.lang.Integer)
     */
    @RolesAllowed("user")
    public boolean thumbnailExists(Integer sizeX, Integer sizeY) {
        // Set defaults and sanity check thumbnail sizes
        errorIfNullPixelsAndRenderingDef();
        Dimension dimensions = sanityCheckThumbnailSizes(sizeX, sizeY);

        Set<Long> pixelsIds = new HashSet<Long>();
        pixelsIds.add(pixelsId);
        ctx.loadAndPrepareMetadata(pixelsIds, dimensions);
        // Ensure that we do not have "dirty" pixels or rendering settings 
        // left around in the Hibernate session cache.
        iQuery.clear();
        return ctx.isThumbnailCached(pixelsId);
    }

    @RolesAllowed("user")
    @Transactional(readOnly = false)
    public void resetDefaults()
    {
        if (settings == null && sec.isGraphCritical())
        {
            throw new ApiUsageException(
                    "Unable to reset rendering settings in a read-only group " +
                    "for Pixels set id:" + pixelsId);
        }
        _resetDefaults();
        iUpdate.flush();
    }

    /** Actually does the work specified by {@link resetDefaults()}.*/
    private void _resetDefaults()
    {
        // Ensure that setPixelsId() has been called first.
        errorIfNullPixels();

        // Ensure that we haven't just been called before setPixelsId() and that
        // the rendering settings are null.
        Parameters params = new Parameters();
        params.addId(pixels.getId());
        params.addLong("o_id", getCurrentUserId());
        if (settings != null
            || iQuery.findByQuery(
                    "from RenderingDef as r where r.pixels.id = :id and " +
                    "r.details.owner.id = :o_id", params) != null)
        {
            throw new ApiUsageException(
                    "The thumbnail service only resets **empty** rendering " +
                    "settings. Resetting of existing settings should either " +
                    "be performed using the RenderingEngine or " +
                    "IRenderingSettings.");
        }

        RenderingDef def = settingsService.createNewRenderingDef(pixels);
        settingsService.resetDefaults(def, pixels);
    }

    public boolean isDiskSpaceChecking() {
        return diskSpaceChecking;
    }

    public void setDiskSpaceChecking(boolean diskSpaceChecking) {
        this.diskSpaceChecking = diskSpaceChecking;
    }
}
