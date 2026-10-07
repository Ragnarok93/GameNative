#version 450

layout(push_constant) uniform PC {
    float ndcX0;
    float ndcY0;
    float ndcX1;
    float ndcY1;
    layout(offset = 60) uint surfaceTransform;
} pc;

layout(location = 0) out vec2 fragTexCoord;

void main() {
    int xi = (gl_VertexIndex >> 1) & 1;
    int yi = gl_VertexIndex & 1;
    float x = xi == 1 ? pc.ndcX1 : pc.ndcX0;
    float y = yi == 1 ? pc.ndcY1 : pc.ndcY0;
    // Inverse of VkSurfaceTransform in Android's positive-height viewport.
    // Mirrored transforms apply the inverse rotation before the mirror.
    uint transform = pc.surfaceTransform;
    if (transform == 2u || transform == 32u) {
        // VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR is clockwise in surface
        // space; compensate with the inverse (counter-clockwise) in NDC.
        float previousX = x; x = -y; y = previousX;
    } else if (transform == 4u || transform == 64u) {
        x = -x; y = -y;
    } else if (transform == 8u || transform == 128u) {
        float previousX = x; x = y; y = -previousX;
    }
    if ((transform & 240u) != 0u) x = -x;
    gl_Position = vec4(x, y, 0.0, 1.0);
    fragTexCoord = vec2(float(xi), float(yi));
}
